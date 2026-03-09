package com.azizbgboss.irc;

import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import java.io.*;
import java.util.Vector;

public class IRCClient implements CommandListener, Runnable {

    // --- Server ---
    private static final String HOST    = "irc.libera.chat";
    private static final int    PORT    = 6667;
    private static final String RMS_KEY = "ircsettings";

    // --- Colors ---
    private static final int COLOR_BG        = 0x0F0F0F;
    private static final int COLOR_TEXT       = 0xE0E0E0;
    private static final int COLOR_NICK_SELF  = 0x57A0D3; // blue
    private static final int COLOR_NICK_OTHER = 0x57D387; // green
    private static final int COLOR_SYSTEM     = 0x888888; // gray
    private static final int COLOR_INPUT_BG   = 0x1E1E1E;
    private static final int COLOR_INPUT_TEXT = 0xFFFFFF;
    private static final int COLOR_DIVIDER    = 0x333333;
    private static final int COLOR_TIMESTAMP  = 0x555555;

    // --- Message types ---
    private static final int MSG_SELF   = 0;
    private static final int MSG_OTHER  = 1;
    private static final int MSG_SYSTEM = 2;

    // --- State ---
    private String  nick;
    private String  server;
    private int     port;
    private boolean forceWifi;
    private String  currentChannel = "";
    private boolean connected      = false;
    private boolean running        = false;

    // --- Messages ---
    private Vector messages    = new Vector(); // String[]{ nick, text, type }
    private Vector timestamps  = new Vector(); // String

    private static final int MAX_MESSAGES = 50;

    // --- IO ---
    private SocketConnection socket;
    private InputStream  in;
    private OutputStream out;
    private Thread readThread;

    // --- UI ---
    private IRCMidlet  midlet;
    private Form       connectForm;
    private TextField  tfNick;
    private TextField  tfChannel;
    private TextField  tfWifi;
    private TextField  tfServer;
    private TextField  tfPort;
    private Command    cmdConnect;
    private Command    cmdQuit;
    private ChatCanvas chatCanvas;
    private TextBox    inputBox;
    private Command    cmdSend;
    private Command    cmdBack;
    private Command    cmdLeave;
    private Command    cmdInputOk;
    private Command    cmdInputCancel;

    public IRCClient(IRCMidlet midlet) {
        this.midlet = midlet;
        buildConnectScreen();
        chatCanvas = new ChatCanvas();
        loadSettings();
    }

    // -------------------------
    // --- RMS ---
    // -------------------------

    private void saveSettings() {
        try {
            RecordStore rs = RecordStore.openRecordStore(RMS_KEY, true);
            String data    = tfNick.getString() + "|" + tfChannel.getString() + "|" + tfWifi.getString() + "|" + tfServer.getString() + "|" + tfPort.getString();
            byte[] bytes   = data.getBytes();
            if (rs.getNumRecords() == 0) {
                rs.addRecord(bytes, 0, bytes.length);
            } else {
                rs.setRecord(1, bytes, 0, bytes.length);
            }
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    private void loadSettings() {
        try {
            RecordStore rs = RecordStore.openRecordStore(RMS_KEY, false);
            if (rs.getNumRecords() > 0) {
                byte[] bytes = rs.getRecord(1);
                String data  = new String(bytes);

                // Manual split by '|' instead of String.split()
                String[] parts = new String[5];
                int count = 0;
                int start = 0;
                for (int i = 0; i <= data.length() && count < 4; i++) {
                    if (i == data.length() || data.charAt(i) == '|') {
                        parts[count++] = data.substring(start, i);
                        start = i + 1;
                    }
                }
                parts[4] = start < data.length() ? data.substring(start) : "";

                if (parts[0] != null && parts[0].length() > 0) tfNick.setString(parts[0]);
                if (parts[1] != null && parts[1].length() > 0) tfChannel.setString(parts[1]);
                if (parts[2] != null && parts[2].length() > 0) tfWifi.setString(parts[2]);
                if (parts[3] != null && parts[3].length() > 0) tfServer.setString(parts[3]);
                if (parts[4] != null && parts[4].length() > 0) tfPort.setString(parts[4]);
            }
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    // -------------------------
    // --- Screen builders ---
    // -------------------------

    private void buildConnectScreen() {
        connectForm = new Form("BBIRC");
        tfNick      = new TextField("Nickname:", "BBUser",   32, TextField.ANY);
        tfChannel   = new TextField("Channel:",  "#libera",  64, TextField.ANY);
        tfWifi      = new TextField("Force Wi-Fi (Y/N):", "N", 1, TextField.ANY);
        tfServer    = new TextField("Server:", HOST, 64, TextField.ANY);
        tfPort      = new TextField("Port:", String.valueOf(PORT), 5, TextField.NUMERIC);
        connectForm.append(tfNick);
        connectForm.append(tfChannel);
        connectForm.append(tfWifi);
        connectForm.append(tfServer);
        connectForm.append(tfPort);
        connectForm.append(new StringItem("Credits:", "Developed by AzizBgBoss"));
        connectForm.append(new StringItem("GitHub:", "github.com/AzizBgBoss/BBIRC"));

        cmdConnect = new Command("Connect", Command.OK,   1);
        cmdQuit    = new Command("Quit",    Command.EXIT, 2);
        connectForm.addCommand(cmdConnect);
        connectForm.addCommand(cmdQuit);
        connectForm.setCommandListener(this);
    }

    private void buildChatCanvas(String channel) {
        chatCanvas = new ChatCanvas();
        chatCanvas.setTitle(channel);

        cmdSend  = new Command("Send",  Command.OK,     1);
        cmdLeave = new Command("Leave", Command.SCREEN, 2);
        cmdBack  = new Command("Back",  Command.BACK,   3);
        chatCanvas.addCommand(cmdSend);
        chatCanvas.addCommand(cmdLeave);
        chatCanvas.addCommand(cmdBack);
        chatCanvas.setCommandListener(this);
    }

    // -------------------------
    // --- Connection ---
    // -------------------------

    private void connect() {
        nick = tfNick.getString().trim();
        String channel = tfChannel.getString().trim();

        if (nick.length() == 0) {
            showAlert("Error", "Enter a nickname.", connectForm);
            return;
        }
        if (channel.length() == 0) {
            showAlert("Error", "Enter a channel.", connectForm);
            return;
        }
        if (!channel.startsWith("#")) channel = "#" + channel;

        server = tfServer.getString().trim();
        if (server.length() == 0) server = HOST;

        port = Integer.parseInt(tfPort.getString());
        if (port == 0) port = PORT;

        String wifi = tfWifi.getString().trim().toUpperCase();
        forceWifi = wifi.equals("Y");

        saveSettings();

        final String finalChannel = channel;

        new Thread(new Runnable() {
            public void run() {
                try {
                    socket = (SocketConnection) Connector.open(
                        "socket://" + server + ":" + port + (forceWifi ? ";interface=wifi" : ""));
                    in  = socket.openInputStream();
                    out = socket.openOutputStream();

                    connected = true;

                    sendRaw("NICK " + nick);
                    sendRaw("USER " + nick + " 0 * :" + nick);

                    running = true;
                    readThread = new Thread(IRCClient.this);
                    readThread.start();

                    joinChannel(finalChannel);

                } catch (Exception e) {
                    showAlert("Error", "Could not connect: " + e.getMessage(), connectForm);
                }
            }
        }).start();
    }

    public void disconnect() {
        running   = false;
        connected = false;
        try {
            if (out    != null) sendRaw("QUIT :BB IRC");
            if (in     != null) in.close();
            if (out    != null) out.close();
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
        addMessage(nick, message, MSG_SELF);
    }

    private void joinChannel(final String channel) {
        currentChannel = channel;
        sendRaw("JOIN " + channel);
        midlet.getDisplay().callSerially(new Runnable() {
            public void run() {
                messages.removeAllElements();
                timestamps.removeAllElements();
                buildChatCanvas(channel);
                midlet.getDisplay().setCurrent(chatCanvas);
            }
        });
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
        running   = false;
        connected = false;
    }

    // -------------------------
    // --- IRC protocol ---
    // -------------------------

    private void handleLine(final String line) {
        if (line.startsWith("PING")) {
            sendRaw("PONG " + line.substring(5));
            return;
        }

        String prefix  = "";
        String command = "";
        String params  = "";
        String rest    = line;

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

        String senderNick = prefix;
        int excl = prefix.indexOf('!');
        if (excl != -1) senderNick = prefix.substring(0, excl);

        if (command.equals("PRIVMSG")) {
            // params = "#channel :message text"
            int spaceColon = params.indexOf(" :");
            if (spaceColon != -1) {
                String target  = params.substring(0, spaceColon).trim();
                String message = params.substring(spaceColon + 2); // skip " :"
                if (target.equals(currentChannel)) {
                    addMessage(senderNick, message, MSG_OTHER);
                }
            }
        } else if (command.equals("JOIN")) {
            if (!senderNick.equals(nick)) {
                addMessage("", "* " + senderNick + " joined", MSG_SYSTEM);
            }
        } else if (command.equals("PART")) {
            addMessage("", "* " + senderNick + " left", MSG_SYSTEM);
        } else if (command.equals("QUIT")) {
            addMessage("", "* " + senderNick + " quit", MSG_SYSTEM);
        } else if (command.equals("353")) {
            int colon = params.lastIndexOf(':');
            if (colon != -1) {
                addMessage("", "* Users: " + params.substring(colon + 1), MSG_SYSTEM);
            }
        } else if (command.equals("433")) {
            nick = nick + "_";
            sendRaw("NICK " + nick);
            sendRaw("USER " + nick + " 0 * :" + nick); // add this line
            addMessage("", "* Nick taken, using: " + nick, MSG_SYSTEM);
        }
    }

    private void notification() {
        try {
            // Vibrate for 200ms
            midlet.getDisplay().vibrate(200);
        } catch (Exception e) {}
    }

    // -------------------------
    // --- Messages ---
    // -------------------------

    private void addMessage(final String msgNick, final String text, final int type) {
        // Timestamp HH:MM
        long now     = System.currentTimeMillis();
        int  hours   = (int)((now / 3600000) % 24);
        int  minutes = (int)((now / 60000)   % 60);
        String ts    = pad(hours) + ":" + pad(minutes);

        if (messages.size() >= MAX_MESSAGES) {
            messages.removeElementAt(0);
            timestamps.removeElementAt(0);
        }

        if (type == MSG_OTHER) {
            notification();
        }

        messages.addElement(new String[]{ msgNick, text, String.valueOf(type) });
        timestamps.addElement(ts);

        midlet.getDisplay().callSerially(new Runnable() {
            public void run() {
                if (chatCanvas != null) chatCanvas.repaint();
            }
        });
    }

    private String pad(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    // -------------------------
    // --- Commands ---
    // -------------------------

    public void commandAction(Command c, Displayable d) {

        if (d == connectForm) {
            if (c == cmdConnect) {
                connect();
            } else if (c == cmdQuit) {
                midlet.quit();
            }
        }

        else if (d == chatCanvas) {
            if (c == cmdSend) {
                // Open input box
                inputBox    = new TextBox("Message", "", 256, TextField.ANY);
                cmdInputOk  = new Command("Send",   Command.OK,   1);
                cmdInputCancel = new Command("Cancel", Command.BACK, 2);
                inputBox.addCommand(cmdInputOk);
                inputBox.addCommand(cmdInputCancel);
                inputBox.setCommandListener(this);
                midlet.getDisplay().setCurrent(inputBox);
            } else if (c == cmdLeave) {
                sendRaw("PART " + currentChannel + " :leaving");
                currentChannel = "";
                disconnect();
                midlet.getDisplay().setCurrent(connectForm);
            } else if (c == cmdBack) {
                // Back goes straight to connect screen
                disconnect();
                midlet.getDisplay().setCurrent(connectForm);
            }
        }

        else if (d == inputBox) {
            if (c.getCommandType() == Command.OK) {
                String msg = inputBox.getString().trim();
                if (msg.length() > 0) {
                    sendMessage(currentChannel, msg);
                }
                midlet.getDisplay().setCurrent(chatCanvas);
            } else {
                midlet.getDisplay().setCurrent(chatCanvas);
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

    // -------------------------
    // --- Chat Canvas ---
    // -------------------------

    private class ChatCanvas extends Canvas {

        private String title = "";

        public void setTitle(String t) { this.title = t; }

        protected void paint(Graphics g) {
            int W = getWidth();
            int H = getHeight();

            // Background
            g.setColor(COLOR_BG);
            g.fillRect(0, 0, W, H);

            Font fontSmall  = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
            Font fontBold   = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD,  Font.SIZE_SMALL);
            int lineH       = fontSmall.getHeight() + 2;

            // --- Title bar ---
            int titleH = fontBold.getHeight() + 6;
            g.setColor(0x1A1A2E);
            g.fillRect(0, 0, W, titleH);
            g.setColor(COLOR_NICK_SELF);
            g.setFont(fontBold);
            g.drawString(title, W / 2, 3, Graphics.TOP | Graphics.HCENTER);
            g.setColor(COLOR_DIVIDER);
            g.drawLine(0, titleH, W, titleH);

            // --- Bottom hint bar ---
            int hintH = fontSmall.getHeight() + 4;
            int hintY = H - hintH;
            g.setColor(COLOR_INPUT_BG);
            g.fillRect(0, hintY, W, hintH);
            g.setColor(COLOR_DIVIDER);
            g.drawLine(0, hintY, W, hintY);
            g.setColor(COLOR_SYSTEM);
            g.setFont(fontSmall);
            g.drawString("Press Back to disconnect", W / 2, hintY + 2,
                Graphics.TOP | Graphics.HCENTER);

            // --- Chat area ---
            int chatTop = titleH + 2;
            int chatBot = hintY - 2;
            int chatH   = chatBot - chatTop;
            int maxLines = chatH / lineH;

            // Draw from bottom up
            int msgCount = messages.size();
            int start    = msgCount - maxLines;
            if (start < 0) start = 0;

            int y = chatTop + (maxLines - (msgCount - start)) * lineH;

            for (int i = start; i < msgCount; i++) {
                String[] msg = (String[]) messages.elementAt(i);
                String   ts  = (String)   timestamps.elementAt(i);
                String   msgNick = msg[0];
                String   text    = msg[1];
                int      type    = Integer.parseInt(msg[2]);

                // Timestamp
                g.setFont(fontSmall);
                g.setColor(COLOR_TIMESTAMP);
                g.drawString(ts + " ", 2, y, Graphics.TOP | Graphics.LEFT);
                int tsW = fontSmall.stringWidth(ts) + 4;

                if (type == MSG_SYSTEM) {
                    g.setColor(COLOR_SYSTEM);
                    g.setFont(fontSmall);
                    g.drawString(text, tsW, y, Graphics.TOP | Graphics.LEFT);
                } else {
                    // Nick
                    g.setFont(fontBold);
                    g.setColor(type == MSG_SELF ? COLOR_NICK_SELF : COLOR_NICK_OTHER);
                    String nickStr = "<" + msgNick + "> ";
                    g.drawString(nickStr, tsW, y, Graphics.TOP | Graphics.LEFT);
                    int nickW = fontBold.stringWidth(nickStr);

                    // Message text — wrap if needed
                    g.setFont(fontSmall);
                    g.setColor(COLOR_TEXT);
                    int maxTextW = W - tsW - nickW - 2;
                    if (fontSmall.stringWidth(text) <= maxTextW) {
                        g.drawString(text, tsW + nickW, y, Graphics.TOP | Graphics.LEFT);
                    } else {
                        // Truncate with ellipsis — full wrapping needs more lines
                        while (text.length() > 0 &&
                               fontSmall.stringWidth(text + "..") > maxTextW) {
                            text = text.substring(0, text.length() - 1);
                        }
                        g.drawString(text + "..", tsW + nickW, y, Graphics.TOP | Graphics.LEFT);
                    }
                }
                y += lineH;
            }
        }
    }
}