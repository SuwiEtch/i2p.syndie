package syndie.gui;

import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;

class FireSelectionListener implements SelectionListener {
    public void widgetSelected(SelectionEvent selectionEvent) { fire(); }
    public void widgetDefaultSelected(SelectionEvent selectionEvent) { fire(); }
    void fire() {}
}