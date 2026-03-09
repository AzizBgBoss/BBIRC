package com.azizbgboss.irc;

import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import java.io.*;

public class IRCClient implements CommandListener, Runnable {

    // --- Server ---
    private static final String HOST = "irc.libera.chat";
    private static final int    PORT = 6667;

    // --- State ---
    private String nick;
    private String currentChannel = "";
    private boolean connected     = false;
    private boolean running       = false;

    // --- IO ---
    private SocketConnection socket;
    private InputStream  in;
    private OutputStream out;
    private Thread readThread;

    // --- UI ---
    private IRCMidlet midlet;

    // Connect screen
    private Form      connectForm;
    private TextField tfNick;
    private Command   cmdConnect;
    private Command   cmdQuit;

    // Channel list screen
    private List      channelList;
    private Command   cmdJoin;
    private Command   cmdNewChannel;
    private Command   cmdDisconnect;

    // Chat screen
    private Form      chatForm;
    private Command   cmdSend;
    private Command   cmdLeave;
    private Command   cmdBack;

    // Input box
    private TextBox   inputBox;
    private Command   cmdOk;
    private Command   cmdCancel;

    // Join input
    private TextBox   joinBox;

    public IRCClient(IRCMidlet midlet) {
        this.midlet = midlet;
        buildConnectScreen();
        buildChannelList();
    }

    // -------------------------
    // --- Screen builders ---
    // -------------------------

    private void buildConnectScreen() {
        connectForm = new Form("IRC - Connect");
        tfNick = new TextField("Nickname:", "BBUser", 32, TextField.ANY);
        connectForm.append(tfNick);
        connectForm.append(new StringItem("Server:", HOST + ":" + PORT));

        cmdConnect = new Command("Connect", Command.OK,   1);
        cmdQuit    = new Command("Quit",    Command.EXIT, 2);
        connectForm.addCommand(cmdConnect);
        connectForm.addCommand(cmdQuit);
        connectForm.setCommandListener(this);
    }

    private void buildChannelList() {
        channelList = new List("Channels", List.IMPLICIT);

        cmdNewChannel = new Command("Join",       Command.OK,     1);
        cmdDisconnect = new Command("Disconnect", Command.EXIT,   2);
        channelList.addCommand(cmdNewChannel);
        channelList.addCommand(cmdDisconnect);
        channelList.setCommandListener(this);
    }

    private void buildChatScreen(String channel) {
        chatForm = new Form(channel);

        cmdSend = new Command("Send", Command.OK,     1);
        cmdLeave = new Command("Leave", Command.SCREEN, 2);
        cmdBack  = new Command("Back",  Command.BACK,   3);
        chatForm.addCommand(cmdSend);
        chatForm.addCommand(cmdLeave);
        chatForm.addCommand(cmdBack);
        chatForm.setCommandListener(this);
    }

    // -------------------------
    // --- Connection ---
    // -------------------------

    private void connect() {
        nick = tfNick.getString().trim();
        if (nick.length() == 0) {
            showAlert("Error", "Enter a nickname.", connectForm);
            return;
        }

        showAlert("Connecting", "Connecting to " + HOST + "...", null);

        new Thread(new Runnable() {
            public void run() {
                try {
                    socket = (SocketConnection) Connector.open(
                        "socket://" + HOST + ":" + PORT);
                    in  = socket.openInputStream();
                    out = socket.openOutputStream();

                    connected = true;

                    // Register with server
                    sendRaw("NICK " + nick);
                    sendRaw("USER " + nick + " 0 * :" + nick);

                    // Start reading
                    running = true;
                    readThread = new Thread(IRCClient.this);
                    readThread.start();

                    // Go to channel list
                    midlet.getDisplay().setCurrent(channelList);

                } catch (Exception e) {
                    showAlert("Error", "Could not connect: " + e.getMessage(), connectForm);
                }
            }
        }).start();
    }

    public void disconnect() {
        running = false;
        connected = false;
        try {
            if (out != null) sendRaw("QUIT :BB IRC");
            if (in  != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (Exception e) {}
    }

    // -------------------------
    // --- Send ---
    // -------------------------

    private void sendRaw(String line) {
        try {
            out.write((line + "\r\n").getBytes());
            out.flush();
        } catch (Exception e) {}
    }

    private void sendMessage(String channel, String message) {
        sendRaw("PRIVMSG " + channel + " :" + message);
        appendChat("<" + nick + "> " + message);
    }

    private void joinChannel(String channel) {
        if (!channel.startsWith("#")) channel = "#" + channel;
        currentChannel = channel;
        sendRaw("JOIN " + channel);
        buildChatScreen(channel);

        // Add to list if not already there
        boolean found = false;
        for (int i = 0; i < channelList.size(); i++) {
            if (channelList.getString(i).equals(channel)) {
                found = true;
                break;
            }
        }
        if (!found) channelList.append(channel, null);

        midlet.getDisplay().setCurrent(chatForm);
    }

    private void leaveChannel(String channel) {
        sendRaw("PART " + channel + " :leaving");
        for (int i = 0; i < channelList.size(); i++) {
            if (channelList.getString(i).equals(channel)) {
                channelList.delete(i);
                break;
            }
        }
        midlet.getDisplay().setCurrent(channelList);
    }

    // -------------------------
    // --- Read loop ---
    // -------------------------

    public void run() {
        StringBuffer lineBuf = new StringBuffer();
        try {
            while (running) {
                int b = in.read();
                if (b == -1) break;
                char c = (char) b;
                if (c == '\n') {
                    String line = lineBuf.toString().trim();
                    lineBuf = new StringBuffer();
                    if (line.length() > 0) handleLine(line);
                } else if (c != '\r') {
                    lineBuf.append(c);
                }
            }
        } catch (Exception e) {
            if (running) showAlert("Disconnected", "Connection lost.", connectForm);
        }
        running = false;
        connected = false;
    }

    // -------------------------
    // --- IRC protocol ---
    // -------------------------

    private void handleLine(final String line) {
        // PING -> PONG (keep alive)
        if (line.startsWith("PING")) {
            String token = line.substring(5);
            sendRaw("PONG " + token);
            return;
        }

        // Parse: :prefix COMMAND params
        String prefix  = "";
        String command = "";
        String params  = "";

        String rest = line;
        if (rest.startsWith(":")) {
            int sp = rest.indexOf(' ');
            if (sp != -1) {
                prefix = rest.substring(1, sp);
                rest   = rest.substring(sp + 1).trim();
            }
        }

        int sp = rest.indexOf(' ');
        if (sp != -1) {
            command = rest.substring(0, sp);
            params  = rest.substring(sp + 1).trim();
        } else {
            command = rest;
        }

        // Sender nick from prefix (nick!user@host)
        String senderNick = prefix;
        int excl = prefix.indexOf('!');
        if (excl != -1) senderNick = prefix.substring(0, excl);

        if (command.equals("PRIVMSG")) {
            // :nick!u@h PRIVMSG #channel :message
            int colon = params.indexOf(':');
            if (colon != -1) {
                String target  = params.substring(0, colon).trim();
                String message = params.substring(colon + 1);
                if (target.equals(currentChannel)) {
                    appendChat("<" + senderNick + "> " + message);
                }
            }
        } else if (command.equals("JOIN")) {
            String channel = params.startsWith(":") ? params.substring(1) : params;
            if (!senderNick.equals(nick)) {
                appendChat("* " + senderNick + " joined " + channel);
            }
        } else if (command.equals("PART") || command.equals("QUIT")) {
            appendChat("* " + senderNick + " left");
        } else if (command.equals("353")) {
            // Names list on join
            int colon = params.lastIndexOf(':');
            if (colon != -1) {
                appendChat("* Users: " + params.substring(colon + 1));
            }
        } else if (command.equals("372") || command.equals("375") || command.equals("376")) {
            // MOTD — ignore
        } else if (command.equals("433")) {
            // Nick already in use
            nick = nick + "_";
            sendRaw("NICK " + nick);
            appendChat("* Nick taken, trying: " + nick);
        }
    }

    // -------------------------
    // --- Chat UI ---
    // -------------------------

    private void appendChat(final String line) {
        midlet.getDisplay().callSerially(new Runnable() {
            public void run() {
                if (chatForm != null) {
                    // Keep chat to last 20 lines to save memory
                    while (chatForm.size() >= 20) {
                        chatForm.delete(0);
                    }
                    chatForm.append(new StringItem(null, line + "\n"));
                }
            }
        });
    }

    // -------------------------
    // --- Commands ---
    // -------------------------

    public void commandAction(Command c, Displayable d) {

        // Connect screen
        if (d == connectForm) {
            if (c == cmdConnect) {
                connect();
            } else if (c == cmdQuit) {
                midlet.quit();
            }
        }

        // Channel list
        else if (d == channelList) {
            if (c == cmdNewChannel || c == List.SELECT_COMMAND) {
                if (c == List.SELECT_COMMAND && channelList.size() > 0) {
                    // Open existing channel
                    String ch = channelList.getString(channelList.getSelectedIndex());
                    currentChannel = ch;
                    buildChatScreen(ch);
                    midlet.getDisplay().setCurrent(chatForm);
                } else {
                    // Join new channel
                    joinBox = new TextBox("Channel name", "#", 64, TextField.ANY);
                    Command ok     = new Command("Join",   Command.OK,   1);
                    Command cancel = new Command("Cancel", Command.BACK, 2);
                    joinBox.addCommand(ok);
                    joinBox.addCommand(cancel);
                    joinBox.setCommandListener(this);
                    midlet.getDisplay().setCurrent(joinBox);
                }
            } else if (c == cmdDisconnect) {
                disconnect();
                midlet.getDisplay().setCurrent(connectForm);
            }
        }

        // Join input
        else if (d == joinBox) {
            if (c.getCommandType() == Command.OK) {
                joinChannel(joinBox.getString().trim());
            } else {
                midlet.getDisplay().setCurrent(channelList);
            }
        }

        // Chat screen
        else if (d == chatForm) {
            if (c == cmdSend) {
                inputBox = new TextBox("Message", "", 256, TextField.ANY);
                cmdOk     = new Command("Send",   Command.OK,   1);
                cmdCancel = new Command("Cancel", Command.BACK, 2);
                inputBox.addCommand(cmdOk);
                inputBox.addCommand(cmdCancel);
                inputBox.setCommandListener(this);
                midlet.getDisplay().setCurrent(inputBox);
            } else if (c == cmdLeave) {
                leaveChannel(currentChannel);
            } else if (c == cmdBack) {
                midlet.getDisplay().setCurrent(channelList);
            }
        }

        // Message input
        else if (d == inputBox) {
            if (c.getCommandType() == Command.OK) {
                String msg = inputBox.getString().trim();
                if (msg.length() > 0) sendMessage(currentChannel, msg);
                midlet.getDisplay().setCurrent(chatForm);
            } else {
                midlet.getDisplay().setCurrent(chatForm);
            }
        }
    }

    private void showAlert(String title, String msg, Displayable next) {
        Alert a = new Alert(title, msg, null, AlertType.INFO);
        a.setTimeout(2000);
        if (next != null) {
            midlet.getDisplay().setCurrent(a, next);
        } else {
            midlet.getDisplay().setCurrent(a);
        }
    }

    public Displayable getConnectScreen() {
        return connectForm;
    }
}