package com.azizbgboss.irc;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.*;

public class IRCMidlet extends MIDlet {
    private Display display;
    private IRCClient client;

    public void startApp() {
        display = Display.getDisplay(this);
        client = new IRCClient(this);
        display.setCurrent(client.getConnectScreen());
    }

    public void pauseApp() {}

    public void destroyApp(boolean unconditional) {
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