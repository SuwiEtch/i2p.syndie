package syndie.db;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.SecureFile;

import syndie.Constants;
import syndie.data.Enclosure;
import syndie.data.SyndieURI;
import static syndie.db.ImportResult.Detail.*;

/**
 * Import a message for the user, using the keys known to that user and
 * storing the data in the database they can access.
 * CLI import
 * --db $dbURL
 * --login $login
 * --pass $pass
 * --in $filename
 * [--reimport $boolean]
 * [--passphrase $bodyPassphrase]
 *
 * A "message" could be a post or a meta.syndie file.
 * Calls ImportPost or ImportMeta.
 */
public class Importer extends CommandImpl {
    private DBClient _client;
    private String _passphrase;
    private String _pbePrompt;
    private SyndieURI _uri;
    
    public Importer(DBClient client) { this(client, (client != null ? client.getPass(): null)); }

    public Importer(DBClient client, String pass) {
        _client = client;
        _passphrase = pass;
    }

    public Importer() {}

    public static String getHelp(String cmd) {
        return "--in $filename [--reimport $boolean] [--passphrase $bodyPassphrase]";
    }

    public DBClient runCommand(Opts args, UI ui, DBClient client) {
        if ( (client == null) || (!client.isLoggedIn()) ) {
            List missing = args.requireOpts(new String[] { "db", "login", "pass", "in" });
            if (missing.size() > 0) {
                ui.errorMessage("Invalid options, missing " + missing);
                ui.commandComplete(-1, null);
                return client;
            }
        } else {
            List missing = args.requireOpts(new String[] { "in" });
            if (missing.size() > 0) {
                ui.errorMessage("Invalid options, missing " + missing);
                ui.commandComplete(-1, null);
                return client;
            }
        }
        InputStream in = null;
        try {
            long nymId = -1;
            if (args.dbOptsSpecified()) {
                if (client == null)
                    client = new DBClient(I2PAppContext.getGlobalContext(), new SecureFile(TextEngine.getRootPath()));
                else
                    client.close();
                client.connect(args.getOptValue("db"));
                nymId = client.getNymId(args.getOptValue("login"), args.getOptValue("pass"));
                if (DBClient.NYM_ID_LOGIN_UNKNOWN == nymId) {
                    ui.errorMessage("Unknown login '" + args.getOptValue("login") + "'");
                    ui.commandComplete(-1, null);
                    return client;
                } else if (DBClient.NYM_ID_PASSPHRASE_INVALID == nymId) {
                    ui.errorMessage("Invalid passphrase");
                    ui.commandComplete(-1, null);
                    return client;
                }
            } else {
                nymId = client.getLoggedInNymId();
                if (nymId < 0) {
                    ui.errorMessage("Login details required");
                    ui.commandComplete(-1, null);
                    return client;
                }
            }
            
            File file = new File(args.getOptValue("in"));
            if (!file.isFile()) {
                ui.errorMessage("File does not exist");
                ui.commandComplete(-1, null);
                return client;
            }
            
            _client = client;
            _passphrase = client.getPass();
            byte sk[] = args.getOptBytes("replySessionKey");
            SessionKey replySessionKey = null;
            if (sk != null)
                replySessionKey = new SessionKey(sk);
            byte replyIV[] = args.getOptBytes("replyIV");
            in = new BufferedInputStream(new FileInputStream(file));
            ImportResult.Result result = processMessage(ui, in, nymId, client.getPass(), args.getOptValue("passphrase"),
                                                        args.getOptBoolean("reimport", false), replyIV, replySessionKey);
            ui.debugMessage("Metadata processed: " + result);
            if (!result.ok()) // successful imports specify whether they were decrypted (exit code of 0) or undecryptable (exit code of 1)
                ui.commandComplete(-1, null);
        } catch (SQLException se) {
            ui.errorMessage("Invalid database URL", se);
            ui.commandComplete(-1, null);
        } catch (IOException ioe) {
            ui.errorMessage("Error importing the message", ioe);
            ui.commandComplete(-1, null);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            //if (client != null) client.close();
        }
        return client;
    }
    
/****
    public static void main(String args[]) {
        try {
        CLI.main(new String[] { "import", 
                                "--db", "jdbc:hsqldb:file:/tmp/cli",
                                "--login", "j",
                                "--pass", "j",
                                "--in", "/tmp/metaOut" });
        } catch (Exception e) { e.printStackTrace(); }
    }
    
    public static void omain(String args[]) {
        if ( (args == null) || (args.length != 4) )
            throw new RuntimeException("Usage: Importer $dbURL $login $password $filenameToImport");
        DBClient client = null;
        try {
            client = new DBClient(I2PAppContext.getGlobalContext(), new SecureFile(TextEngine.getRootPath()));
            client.connect(args[0]);
            long nymId = client.getNymId(args[1], args[2]);
            if (DBClient.NYM_ID_LOGIN_UNKNOWN == nymId)
                throw new RuntimeException("Unknown login");
            else if (DBClient.NYM_ID_PASSPHRASE_INVALID == nymId)
                throw new RuntimeException("Invalid passphrase");
            
            File file = new File(args[3]);
            if (!file.isFile())
                throw new RuntimeException("File does not exist");
            
            Importer imp = new Importer(client, args[2]);
            //imp.processMessage(new FileInputStream(file), nymId, args[2]);
        } catch (SQLException se) {
            throw new RuntimeException("Invalid database URL: " + se.getMessage(), se);
        } finally {
            if (client != null) client.close();
        }
    }
****/
    
    /*
     * A "message" could be a post or a meta.syndie file.
     *
     * Caller must close the InputStream
     */
    public ImportResult.Result processMessage(UI ui, DBClient client, InputStream source, String bodyPassphrase,
                                  boolean forceReimport, byte replyIV[], SessionKey replySessionKey) throws IOException {
        return processMessage(ui, source, client.getLoggedInNymId(), client.getPass(), bodyPassphrase, forceReimport, replyIV, replySessionKey);
    }

    /** 
     * process the message, importing it if possible.  If it was imported but
     * could not be decrypted (meaning that it is authentic and/or authorized),
     * it will fire ui.commandComplete with an exit value of 1.  if it was imported
     * and read, it will fire ui.commandComplete with an exit value of 0.  otherwise,
     * it will not fire an implicit ui.commandComplete.
     *
     * A "message" could be a post or a meta.syndie file.
     *
     * Caller must close the InputStream
     */
    public ImportResult.Result processMessage(UI ui, InputStream source, long nymId, String pass, String bodyPassphrase,
                                  boolean forceReimport, byte replyIV[], SessionKey replySessionKey) throws IOException {
        if (bodyPassphrase != null)
            ui.debugMessage("Importing message with body passphrase " + bodyPassphrase);
        else
            ui.debugMessage("Importing message with no body passphrase");
        
        // we may be importing something that gives us keys or something that has metadata
        // we'd consider in our cache
        _client.clearNymChannelCache();
        
        ImportResult.Result rv;
        boolean isMeta = false;
        Enclosure enc = new Enclosure(source);
        try {
            String format = enc.getEnclosureType();
            if (format == null) {
                throw new IOException("No enclosure type");
            } else if (!format.startsWith(Constants.TYPE_PREFIX)) {
                throw new IOException("Unsupported enclosure format: " + format);
            }

            _pbePrompt = enc.getHeaderString(Constants.MSG_HEADER_PBE_PROMPT);
            
            String type = enc.getHeaderString(Constants.MSG_HEADER_TYPE);
            if (Constants.MSG_TYPE_META.equals(type)) { // validate and import metadata message
                rv = importMeta(ui, enc, nymId, bodyPassphrase);
                if (rv == IMPORT_UNREADABLE) {
                    if (_pbePrompt != null)
                        rv = IMPORT_PASS_REQD;
                }
                isMeta = true;
            } else if (Constants.MSG_TYPE_POST.equals(type)) { // validate and import content message
                rv = importPost(ui, enc, nymId, pass, bodyPassphrase, forceReimport, null, null);
                if (rv == IMPORT_UNREADABLE) {
                    if (_pbePrompt != null)
                        rv = IMPORT_PASS_REQD;
                    else
                        rv = IMPORT_NO_READ_KEY;
                }
            } else if (Constants.MSG_TYPE_REPLY.equals(type)) { // validate and import reply message
                rv = importPost(ui, enc, nymId, pass, bodyPassphrase, forceReimport, replyIV, replySessionKey);
                if (rv == IMPORT_UNREADABLE) {
                    if (_pbePrompt != null)
                        rv = IMPORT_PASS_REQD;
                    else
                        rv = IMPORT_NO_REPLY_KEY;
                }
            } else {
                throw new IOException("Invalid message type: " + type);
            }
        } finally {
            enc.discardData();
        }
        return rv;
    }

    public String getPBEPrompt() { return _pbePrompt; }

    public SyndieURI getURI() { return _uri; }
    
    protected ImportResult.Result importMeta(UI ui, Enclosure enc, long nymId, String bodyPassphrase) {
        // first check that the metadata is signed by an authorized key
        if (alreadyKnownMeta(ui, enc)) {
            ui.debugMessage("Already have meta");
            return IMPORT_ALREADY;
        } else if (verifyMeta(ui, enc)) {
            return ImportMeta.process(_client, ui, enc, nymId, _passphrase, bodyPassphrase);
        } else {
            ui.errorMessage("meta does not verify");
            return IMPORT_BAD_META_VERIFY;
        }
    }

    /**
     *  This checks only the DB, not if the file exists.
     *  So always return false and let it pass to ImportMeta.process(),
     *  which does the same checks, but saves the file if it does not exist.
     *
     *  @return false always, for now
     */
    private boolean alreadyKnownMeta(UI ui, Enclosure enc) {
     /****
        SigningPublicKey pubKey = enc.getHeaderSigningKey(Constants.MSG_META_HEADER_IDENTITY);
        Long edition = enc.getHeaderLong(Constants.MSG_META_HEADER_EDITION);
        if ( (pubKey != null) && (edition != null) ) {
            long known = _client.getKnownEdition(pubKey.calculateHash());
            // we could check for == here and let old meta to proceed with importing (in case they
            // included keys/headers/etc that were subsequently removed), but the importmeta would
            // drop off the import process prior to that, since this is an old edition.
            if (known >= edition.longValue()) 
                return true;
        }
      ****/
        return false;
    }

    /**
     * The metadata message is ok if it is either signed by the channel's
     * identity itself or by one of the manager keys
     */
    private boolean verifyMeta(UI ui, Enclosure enc) {
        SigningPublicKey pubKey = enc.getHeaderSigningKey(Constants.MSG_META_HEADER_IDENTITY);
        Signature sig = enc.getAuthorizationSig();
        boolean ok = verifySig(_client, sig, enc.getAuthorizationHash(), pubKey);
        if (!ok) {
            ui.debugMessage("authorization hash does not match identity (authHash: " + enc.getAuthorizationHash().toBase64() + " sig: " + sig.toBase64() + ")");
            SigningPublicKey pubKeys[] = enc.getHeaderSigningKeys(Constants.MSG_META_HEADER_MANAGER_KEYS);
            if (pubKeys != null) {
                for (int i = 0; i < pubKeys.length; i++) {
                    if (verifySig(_client, sig, enc.getAuthorizationHash(), pubKeys[i])) {
                        ui.debugMessage("authorization hash matches a manager key");
                        ok = true;
                        break;
                    } else {
                        ui.debugMessage("authorization hash does not match manager key " + pubKeys[i].toBase64());
                    }
                }
            }
        } else {
            ui.debugMessage("authorization hash matches");
            boolean authenticated = verifySig(_client, enc.getAuthenticationSig(), enc.getAuthenticationHash(), pubKey);
            if (authenticated)
                ui.debugMessage("authentication hash matches");
            else
                ui.debugMessage("authentication hash does not match the identity, but that's alright");
            _uri = SyndieURI.createScope(pubKey.calculateHash());
        }
        return ok;
    }
    
    protected ImportResult.Result importPost(UI ui, Enclosure enc, long nymId, String pass, String bodyPassphrase, boolean forceReimport, byte replyIV[], SessionKey replySessionKey) {
        ImportPost post = new ImportPost(_client, ui, enc, nymId, pass, bodyPassphrase, forceReimport, replyIV, replySessionKey);
        ImportResult.Result rv = post.process();
        _uri = post.getURI();
        return rv;
    }
}
