package com.azizbgboss.irc;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.*;

public class IRCMidlet extends MIDlet {
    private Display   display;
    private IRCClient client;

    public void startApp() {
        display = Display.getDisplay(this);
        if (client == null) {
            client = new IRCClient(this);
            display.setCurrent(client.getMainScreen());
        } else {
            // Resumed from background — go back to chat if connected
            client.resume();
        }
    }

    public void pauseApp() {
        client.goBackground(); // socket stays open
    }

    public void destroyApp(boolean unconditional) {
        // Only called on real app kill, not red phone button
        client.disconnect();
        notifyDestroyed();
    }

    public void quit() {
        client.disconnect();
        notifyDestroyed();
    }

    public Display getDisplay() {
        return display;
    }
}