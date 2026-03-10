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
    private static final int COLOR_NICK_SELF  = 0x57A0D3;
    private static final int COLOR_NICK_OTHER = 0x57D387;
    private static final int COLOR_SYSTEM     = 0x888888;
    private static final int COLOR_INPUT_BG   = 0x1E1E1E;
    private static final int COLOR_DIVIDER    = 0x333333;
    private static final int COLOR_TIMESTAMP  = 0x555555;

    // --- Message types ---
    private static final int MSG_SELF   = 0;
    private static final int MSG_OTHER  = 1;
    private static final int MSG_SYSTEM = 2;

    // --- State ---
    private String  nick;
    private String  altNick;
    private String  server;
    private String  nsPassword; // optional NickServ password
    private int     port;
    private boolean forceWifi;
    private String  currentChannel = "";
    private boolean connected      = false;
    private boolean running        = false;
    private boolean registered     = false;
    private boolean connecting     = false;  // guard against double connect
    private String  pendingChannel = null;

    // --- Messages ---
    private Vector messages   = new Vector();
    private Vector timestamps = new Vector();
    private StringBuffer namesBuffer = new StringBuffer();
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
    private TextField  tfAltNick;
    private TextField  tfChannel;
    private TextField  tfWifi;
    private TextField  tfServer;
    private TextField  tfPort;
    private TextField  tfPassword;  // NickServ password
    private Command    cmdConnect;
    private Command    cmdQuit;
    private ChatCanvas chatCanvas;
    private TextBox    inputBox;
    private Command    cmdSend;
    private Command    cmdLeave;
    private Command    cmdClear;
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
            String data  = tfNick.getString() + "|" +
                           tfChannel.getString() + "|" +
                           tfWifi.getString() + "|" +
                           tfServer.getString() + "|" +
                           tfPort.getString() + "|" +
                           tfPassword.getString() + "|" +
                           tfAltNick.getString();
            byte[] bytes = data.getBytes();
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
                // 7 fields: nick|channel|wifi|server|port|password|altNick
                String[] parts = new String[7];
                for (int i = 0; i < parts.length; i++) parts[i] = "";
                int count = 0, start = 0;
                for (int i = 0; i <= data.length() && count < 6; i++) {
                    if (i == data.length() || data.charAt(i) == '|') {
                        parts[count++] = data.substring(start, i);
                        start = i + 1;
                    }
                }
                // last field (altNick) — remainder after 6th pipe
                if (count == 6 && start <= data.length()) {
                    parts[6] = data.substring(start);
                }

                if (parts[0].length() > 0) tfNick.setString(parts[0]);
                if (parts[1].length() > 0) tfChannel.setString(parts[1]);
                if (parts[2].length() > 0) tfWifi.setString(parts[2]);
                if (parts[3].length() > 0) tfServer.setString(parts[3]);
                if (parts[4].length() > 0) tfPort.setString(parts[4]);
                if (parts[5].length() > 0) tfPassword.setString(parts[5]);
                if (parts[6].length() > 0) tfAltNick.setString(parts[6]);
            }
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    // -------------------------
    // --- Screen builders ---
    // -------------------------

    private void buildConnectScreen() {
        connectForm = new Form("BBIRC v" + midlet.getAppProperty("MIDlet-Version"));
        tfNick     = new TextField("Nickname:", "BBUser",  32, TextField.ANY);
        tfAltNick  = new TextField("Alt Nick (optional):", "BBUser_", 32, TextField.ANY);
        tfChannel  = new TextField("Channel:",  "#libera", 64, TextField.ANY);
        tfWifi     = new TextField("Force Wi-Fi (Y/N):", "N", 1, TextField.ANY);
        tfServer   = new TextField("Server:", HOST, 64, TextField.ANY);
        tfPort     = new TextField("Port:", String.valueOf(PORT), 5, TextField.NUMERIC);
        tfPassword = new TextField("NickServ Password:", "", 64, TextField.PASSWORD);
        connectForm.append(tfNick);
        connectForm.append(tfAltNick);
        connectForm.append(tfChannel);
        connectForm.append(tfWifi);
        connectForm.append(tfServer);
        connectForm.append(tfPort);
        connectForm.append(tfPassword);
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
        cmdLeave = new Command("Leave", Command.EXIT,   2);
        cmdClear = new Command("Clear", Command.SCREEN, 3);
        chatCanvas.addCommand(cmdSend);
        chatCanvas.addCommand(cmdLeave);
        chatCanvas.addCommand(cmdClear);
        chatCanvas.setCommandListener(this);
    }

    // -------------------------
    // --- Connection ---
    // -------------------------

    private void connect() {
        // Guard: don't connect if already connected or connecting
        if (connected || connecting) {
            if (connected) {
                // Already connected, just go back to chat
                midlet.getDisplay().setCurrent(chatCanvas);
            } else {
                showAlert("Please wait", "Already connecting...", connectForm);
            }
            return;
        }

        nick = tfNick.getString().trim();
        altNick = tfAltNick.getString().trim();
        String channel = tfChannel.getString().trim();
        nsPassword = tfPassword.getString().trim();

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

        try {
            port = Integer.parseInt(tfPort.getString().trim());
        } catch (Exception e) {
            port = PORT;
        }
        if (port <= 0) port = PORT;

        String wifi = tfWifi.getString().trim().toUpperCase();
        forceWifi = wifi.equals("Y");

        saveSettings();

        // show chat canvas right away with a connecting indication
        buildChatCanvas(channel);
        chatCanvas.setTitle("Connecting...");
        addMessage("", "* Connecting...", MSG_SYSTEM);
        midlet.getDisplay().setCurrent(chatCanvas);

        connecting = true;
        final String finalChannel = channel;

        new Thread(new Runnable() {
            public void run() {
                try {
                    socket = (SocketConnection) Connector.open(
                        "socket://" + server + ":" + port +
                        (forceWifi ? ";interface=wifi" : ""));
                    in  = socket.openInputStream();
                    out = socket.openOutputStream();

                    connected      = true;
                    connecting     = false;
                    registered     = false;
                    pendingChannel = finalChannel;

                    sendRaw("NICK " + nick);
                    sendRaw("USER " + nick + " 0 * :" + nick);

                    running    = true;
                    readThread = new Thread(IRCClient.this);
                    readThread.start();

                } catch (Exception e) {
                    connecting = false;
                    showAlert("Error", "Could not connect: " + e.getMessage(), connectForm);
                }
            }
        }).start();
    }

    // Full disconnect — called only from Leave or app destroy
    public void disconnect() {
        running    = false;
        connecting = false;
        try {
            if (connected && out != null) sendRaw("QUIT :BB IRC");
        } catch (Exception e) {}
        connected  = false;
        registered = false;
        pendingChannel = null;
        closeIO();
    }

    // Just close sockets quietly on error
    private void closeIO() {
        try { if (in     != null) in.close();     } catch (Exception e) {}
        try { if (out    != null) out.close();    } catch (Exception e) {}
        try { if (socket != null) socket.close(); } catch (Exception e) {}
        in = null; out = null; socket = null;
    }

    // -------------------------
    // --- Send ---
    // -------------------------

    private boolean sendRaw(String line) {
        try {
            if (!connected || out == null) return false;
            out.write((line + "\r\n").getBytes());
            out.flush();
            return true;
        } catch (Exception e) {
            handleIOError(e);
            return false;
        }
    }

    private void sendMessage(String channel, String message) {
        boolean ok = sendRaw("PRIVMSG " + channel + " :" + message);
        if (ok) {
            addMessage(nick, message, MSG_SELF);
        } else {
            addMessage("", "* failed to send", MSG_SYSTEM);
        }
    }

    private void joinChannel(final String channel) {
        currentChannel = channel;
        sendRaw("JOIN " + channel);
        midlet.getDisplay().callSerially(new Runnable() {
            public void run() {
                // do not clear messages; keep status lines like "Connecting..." and "Connected!"
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
                    lineBuf.setLength(0);
                    if (line.length() > 0) handleLine(line);
                } else if (c != '\r') {
                    lineBuf.append(c);
                }
            }
        } catch (Exception e) {
            if (running) {
                showAlert("Disconnected", "Connection lost: " + e.getMessage(), connectForm);
            }
        }
        running    = false;
        connected  = false;
        registered = false;
        closeIO();
    }

    // -------------------------
    // --- IRC protocol ---
    // -------------------------

    private void handleLine(String line) {
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
            int spaceColon = params.indexOf(" :");
            if (spaceColon != -1) {
                String target  = params.substring(0, spaceColon).trim();
                String message = params.substring(spaceColon + 2);
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
                String chunk = params.substring(colon + 1).trim();
                if (namesBuffer.length() > 0) namesBuffer.append(' ');
                namesBuffer.append(chunk);
            }
        } else if (command.equals("366")) {
            // end of names — now display
            String names = namesBuffer.toString().trim();
            namesBuffer.setLength(0);
            if (names.length() == 0) return;

            Vector nicks = new Vector();
            int s = 0;
            for (int i = 0; i <= names.length(); i++) {
                if (i == names.length() || names.charAt(i) == ' ') {
                    if (i > s) nicks.addElement(names.substring(s, i));
                    s = i + 1;
                }
            }
            int total = nicks.size();
            int show  = total > 10 ? 10 : total;
            StringBuffer sb = new StringBuffer("* Users: ");
            for (int i = 0; i < show; i++) {
                if (i > 0) sb.append(' ');
                sb.append((String) nicks.elementAt(i));
            }
            if (total > 10) sb.append(" +").append(total - 10).append(" more");
            addMessage("", sb.toString(), MSG_SYSTEM);
        } else if (command.equals("433")) {
            if (altNick != null && altNick.length() > 0) {
                nick = altNick;
                altNick = "";
                sendRaw("NICK " + nick);
                addMessage("", "* Nick taken, using: " + nick, MSG_SYSTEM);
            } else {
                nick = nick + "_";
                sendRaw("NICK " + nick);
                addMessage("", "* Nick taken, using: " + nick, MSG_SYSTEM);
            }
        } else if (command.equals("001")) {
            if (!registered) {
                registered = true;
                addMessage("", "* Connected!", MSG_SYSTEM);
                if (nsPassword != null && nsPassword.length() > 0) {
                    sendRaw("PRIVMSG NickServ :IDENTIFY " + nsPassword);
                    addMessage("", "* Identifying with NickServ", MSG_SYSTEM);
                }
                if (pendingChannel != null) {
                    joinChannel(pendingChannel);
                    pendingChannel = null;
                }
            }
        }
    }

    // -------------------------
    // --- Notifications ---
    // -------------------------

    private void notification() {
        try {
            midlet.getDisplay().vibrate(100);
        } catch (Exception e) {}
    }

    private void handleIOError(Exception e) {
        connected  = false;
        running    = false;
        registered = false;
        closeIO();
        showAlert("Error", "Network error: " + e.getMessage(), connectForm);
    }

    // -------------------------
    // --- Messages ---
    // -------------------------

    private synchronized void addMessage(final String msgNick,
                                         final String text,
                                         final int    type) {
        long now     = System.currentTimeMillis();
        int  hours   = (int)((now / 3600000) % 24);
        int  minutes = (int)((now / 60000)   % 60);
        String ts    = pad(hours) + ":" + pad(minutes);

        if (messages.size() >= MAX_MESSAGES) {
            messages.removeElementAt(0);
            timestamps.removeElementAt(0);
        }

        if (type == MSG_OTHER) notification();

        messages.addElement(new String[]{ msgNick, text, String.valueOf(type) });
        timestamps.addElement(ts);

        midlet.getDisplay().callSerially(new Runnable() {
            public void run() {
                if (chatCanvas != null) {
                    chatCanvas.resetScroll();
                    chatCanvas.repaint();
                }
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
                disconnect();
                midlet.quit();
            }
        }

        else if (d == chatCanvas) {
            if (c == cmdSend) {
                inputBox       = new TextBox("Message", "", 256, TextField.ANY);
                cmdInputOk     = new Command("Send",   Command.OK,   1);
                cmdInputCancel = new Command("Cancel", Command.BACK, 2);
                inputBox.addCommand(cmdInputOk);
                inputBox.addCommand(cmdInputCancel);
                inputBox.setCommandListener(this);
                midlet.getDisplay().setCurrent(inputBox);
            } else if (c == cmdLeave) {
                // Full disconnect — user explicitly left
                sendRaw("PART " + currentChannel + " :leaving");
                disconnect();
                midlet.getDisplay().setCurrent(connectForm);
            } else if (c == cmdClear) {
                // Just clear messages, keep connection and channel
                messages.removeAllElements();
                timestamps.removeAllElements();
                if (chatCanvas != null) {
                    chatCanvas.resetScroll();
                    chatCanvas.repaint();
                }
            }
            // Red phone button (destroyApp) is handled in IRCMidlet — does nothing here
        }

        else if (d == inputBox) {
            if (c.getCommandType() == Command.OK) {
                String msg = inputBox.getString().trim().replace('\n', ' ');
                if (msg.length() > 0) sendMessage(currentChannel, msg);
                midlet.getDisplay().setCurrent(chatCanvas);
            } else {
                midlet.getDisplay().setCurrent(chatCanvas);
            }
        }
    }

    // Called by IRCMidlet.pauseApp() — keep connection alive, just hide
    public void goBackground() {
        // do nothing — socket stays open
    }

    // Called by IRCMidlet.startApp() on resume — go back to chat if connected
    public void resume() {
        if (connected && chatCanvas != null) {
            midlet.getDisplay().setCurrent(chatCanvas);
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

        private int[] cachedMsgLines = new int[0];
        private int   cachedMsgCount = 0;
        private int   cachedWidth    = 0;
        private String title = "";
        private int scrollOffset = 0; // lines scrolled up from bottom

        protected void keyPressed(int keyCode) {
            int action = getGameAction(keyCode);
            if (action == UP) {
                scrollOffset++;
                repaint();
            } else if (action == DOWN) {
                if (scrollOffset > 0) scrollOffset--;
                repaint();
            }
        }

        public void resetScroll() {
            scrollOffset = 0;
            cachedMsgCount = -1; // force cache rebuild
        }

        public void setTitle(String t) { this.title = t; }

        private void rebuildCache(Font fontSmall, int W) {
            int msgCount = messages.size();
            cachedMsgLines = new int[msgCount];
            synchronized (IRCClient.this) {
                for (int i = 0; i < msgCount; i++) {
                    String[] msg     = (String[]) messages.elementAt(i);
                    String   text    = msg[1];
                    int      type    = Integer.parseInt(msg[2]);
                    boolean  grouped = isGrouped(i);
                    if (type == MSG_SYSTEM) {
                        int tsW = fontSmall.stringWidth("00:00 ");
                        cachedMsgLines[i] = wrapText(text, fontSmall, W - tsW - 2).length;
                    } else if (grouped) {
                        cachedMsgLines[i] = wrapText(text, fontSmall, W - 2).length;
                    } else {
                        cachedMsgLines[i] = 1 + wrapText(text, fontSmall, W - 2).length;
                    }
                }
            }
            cachedMsgCount = msgCount;
            cachedWidth    = W;
        }

        private String[] wrapText(String text, Font font, int maxWidth) {
            Vector lines = new Vector();
            while (text.length() > 0) {
                if (font.stringWidth(text) <= maxWidth) {
                    lines.addElement(text);
                    break;
                }
                // find last space that fits
                int cut = text.length() - 1;
                while (cut > 0 && font.stringWidth(text.substring(0, cut)) > maxWidth) {
                    cut--;
                }
                // if no space found, hard break at cut
                int space = text.lastIndexOf(' ', cut);
                if (space > 0) cut = space;
                lines.addElement(text.substring(0, cut));
                text = text.substring(cut).trim();
            }
            if (lines.isEmpty()) lines.addElement("");
            String[] result = new String[lines.size()];
            for (int i = 0; i < lines.size(); i++) {
                result[i] = (String) lines.elementAt(i);
            }
            return result;
        }

        // Returns true if message i should be grouped with the previous one:
        // same sender, not system, and within 10 minutes
        private boolean isGrouped(int i) {
            if (i == 0) return false;
            String[] cur  = (String[]) messages.elementAt(i);
            String[] prev = (String[]) messages.elementAt(i - 1);
            int curType  = Integer.parseInt(cur[2]);
            int prevType = Integer.parseInt(prev[2]);
            if (curType  == MSG_SYSTEM) return false;
            if (prevType == MSG_SYSTEM) return false;
            // same nick?
            if (!cur[0].equals(prev[0])) return false;
            // within 10 minutes? compare timestamps "HH:MM"
            String tsCur  = (String) timestamps.elementAt(i);
            String tsPrev = (String) timestamps.elementAt(i - 1);
            int minCur  = tsToMinutes(tsCur);
            int minPrev = tsToMinutes(tsPrev);
            int diff = minCur - minPrev;
            if (diff < 0) diff += 1440; // midnight rollover
            return diff <= 10;
        }

        private int tsToMinutes(String ts) {
            // ts format: "HH:MM"
            try {
                int h = Integer.parseInt(ts.substring(0, 2));
                int m = Integer.parseInt(ts.substring(3, 5));
                return h * 60 + m;
            } catch (Exception e) {
                return 0;
            }
        }

        protected void paint(Graphics g) {
            int W = getWidth();
            int H = getHeight();

            g.setColor(COLOR_BG);
            g.fillRect(0, 0, W, H);

            Font fontSmall = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
            Font fontBold  = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD,  Font.SIZE_SMALL);
            int  lineH     = fontSmall.getHeight() + 2;

            // Title bar
            int titleH = fontBold.getHeight() + 6;
            g.setColor(0x1A1A2E);
            g.fillRect(0, 0, W, titleH);
            g.setColor(COLOR_NICK_SELF);
            g.setFont(fontBold);
            g.drawString(title, W / 2, 3, Graphics.TOP | Graphics.HCENTER);
            g.setColor(COLOR_DIVIDER);
            g.drawLine(0, titleH, W, titleH);

            // Bottom hint bar
            int hintH = fontSmall.getHeight() + 4;
            int hintY = H - hintH;
            g.setColor(COLOR_INPUT_BG);
            g.fillRect(0, hintY, W, hintH);
            g.setColor(COLOR_DIVIDER);
            g.drawLine(0, hintY, W, hintY);
            g.setColor(COLOR_SYSTEM);
            g.setFont(fontSmall);
            g.drawString("Press Options to send/leave", W / 2, hintY + 2,
                Graphics.TOP | Graphics.HCENTER);

            int chatTop  = titleH + 2;
            int chatBot  = hintY - 2;
            int maxLines = (chatBot - chatTop) / lineH;

            int msgCount = messages.size();
            if (msgCount == 0) return;

            // --- count lines per message (cached) ---
            if (msgCount != cachedMsgCount || W != cachedWidth) {
                rebuildCache(fontSmall, W);
            }
            int[] msgLines = cachedMsgLines;

            // total lines across all messages
            int totalLines = 0;
            for (int i = 0; i < msgCount; i++) totalLines += msgLines[i];

            // clamp scroll to valid range
            int maxScroll = totalLines - maxLines;
            if (maxScroll < 0) maxScroll = 0;
            if (scrollOffset > maxScroll) scrollOffset = maxScroll;
            if (scrollOffset < 0) scrollOffset = 0;

            // skip lines from top to find start message
            int skipLines = totalLines - maxLines - scrollOffset;
            if (skipLines < 0) skipLines = 0;

            int start = 0;
            int skipped = 0;
            while (start < msgCount && skipped + msgLines[start] <= skipLines) {
                skipped += msgLines[start];
                start++;
            }
            int partialSkip = skipLines - skipped;

            int y = chatTop - partialSkip * lineH;

            // clip to chat area only
            g.setClip(0, chatTop, W, chatBot - chatTop);

            synchronized (IRCClient.this) {
                for (int i = start; i < msgCount; i++) {
                    String[] msg     = (String[]) messages.elementAt(i);
                    String   ts      = (String)   timestamps.elementAt(i);
                    String   msgNick = msg[0];
                    String   text    = msg[1];
                    int      type    = Integer.parseInt(msg[2]);
                    boolean  grouped = isGrouped(i);

                    int tsW = fontSmall.stringWidth(ts + " ");

                    if (type == MSG_SYSTEM) {
                        g.setFont(fontSmall);
                        g.setColor(COLOR_TIMESTAMP);
                        g.drawString(ts + " ", 2, y, Graphics.TOP | Graphics.LEFT);
                        String[] wrapped = wrapText(text, fontSmall, W - tsW - 2);
                        for (int w = 0; w < wrapped.length; w++) {
                            g.setColor(COLOR_SYSTEM);
                            g.drawString(wrapped[w], tsW, y, Graphics.TOP | Graphics.LEFT);
                            y += lineH;
                        }
                    } else if (grouped) {
                        String[] wrapped = wrapText(text, fontSmall, W - 2);
                        for (int w = 0; w < wrapped.length; w++) {
                            g.setFont(fontSmall);
                            g.setColor(COLOR_TEXT);
                            g.drawString(wrapped[w], 2, y, Graphics.TOP | Graphics.LEFT);
                            y += lineH;
                        }
                    } else {
                        g.setFont(fontSmall);
                        g.setColor(COLOR_TIMESTAMP);
                        g.drawString(ts + " ", 2, y, Graphics.TOP | Graphics.LEFT);
                        g.setFont(fontBold);
                        g.setColor(type == MSG_SELF ? COLOR_NICK_SELF : msgNick.hashCode() % 0x1000000); // Color unique to every name
                        g.drawString(msgNick + ":", tsW, y, Graphics.TOP | Graphics.LEFT);
                        y += lineH;

                        String[] wrapped = wrapText(text, fontSmall, W - 2);
                        for (int w = 0; w < wrapped.length; w++) {
                            g.setFont(fontSmall);
                            g.setColor(COLOR_TEXT);
                            g.drawString(wrapped[w], 2, y, Graphics.TOP | Graphics.LEFT);
                            y += lineH;
                        }
                    }

                    if (y >= chatBot) break;
                }
            }

            g.setClip(0, 0, W, H);
        }
    }
}