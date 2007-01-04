package syndie.gui;

import syndie.db.CLI;
import syndie.db.DBClient;
import syndie.db.Opts;
import syndie.db.UI;

public class BrowserCommand implements CLI.Command {
    public BrowserCommand() {}
    public DBClient runCommand(Opts opts, UI ui, DBClient client) {
        Browser browser = new Browser(client);
        browser.startup();
        return client;
    }
}