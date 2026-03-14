package com.azizbgboss.irc;

import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import java.io.*;
import java.util.Vector;

public class IRCClient implements CommandListener, Runnable {

    // --- Server defaults ---
    private static final String DEFAULT_HOST = "irc.libera.chat";
    private static final int DEFAULT_PORT = 6667;

    // --- RMS keys ---
    private static final String RMS_PROFILES = "ircprofiles";
    private static final String RMS_ACTIVE = "ircactive";
    private static final int MAX_PROFILES = 5;

    // --- Colors ---
    private static final int COLOR_BG = 0x0F0F0F;
    private static final int COLOR_TEXT = 0xE0E0E0;
    private static final int COLOR_NICK_SELF = 0x57A0D3;
    private static final int COLOR_SYSTEM = 0x888888;
    private static final int COLOR_INPUT_BG = 0x1E1E1E;
    private static final int COLOR_DIVIDER = 0x333333;
    private static final int COLOR_TIMESTAMP = 0x555555;

    // --- Message types ---
    private static final int MSG_SELF = 0;
    private static final int MSG_OTHER = 1;
    private static final int MSG_SYSTEM = 2;

    // --- Connection state ---
    private String nick = "";
    private String altNick = "";
    private String server = DEFAULT_HOST;
    private String nsPassword = "";
    private int port = DEFAULT_PORT;
    private boolean forceWifi = false;
    private String currentChannel = "";
    private boolean connected = false;
    private boolean running = false;
    private boolean registered = false;
    private boolean connecting = false;
    private String pendingChannel = null;

    // --- Messages ---

    private Vector messages = new Vector();

    private Vector timestamps = new Vector();
    private StringBuffer namesBuffer = new StringBuffer();

    // Private messages
    private static final int MAX_PRIVATE_TABS = 10;

    private Vector privateTabs = new Vector();

    private Vector[] privateMessages = new Vector[MAX_PRIVATE_TABS];

    private Vector[] privateTimestamps = new Vector[MAX_PRIVATE_TABS];

    private static final int MAX_MESSAGES = 50;

    // --- IO ---
    private SocketConnection socket;
    private InputStream in;
    private OutputStream out;
    private Thread readThread;

    // --- Profiles (in-memory) ---
    // Each element is a String[8]:
    // [0] profileName
    // [1] nick
    // [2] altNick
    // [3] channel
    // [4] wifi (Y/N)
    // [5] server
    // [6] port
    // [7] password

    private Vector profiles = new Vector(); // Vector of String[]
    private int activeProfile = 0; // index into profiles
    private String activeTab = null; // current tab (channel or private nick)

    private String notificationMsg = null;

    private Vector nicks = null;

    // --- UI: MIDlet ---
    private IRCMidlet midlet;

    // --- UI: Main menu ---
    private Form mainForm;
    private StringItem siProfileName;
    private Command cmdMainConnect;
    private Command cmdMainProfiles;
    private Command cmdMainQuit;

    // --- UI: Profile list ---
    private List profileList;
    private Command cmdPlNew;
    private Command cmdPlEdit;
    private Command cmdPlDuplicate;
    private Command cmdPlDelete;
    private Command cmdPlSelect;
    private Command cmdPlBack;

    // --- UI: Profile edit form ---
    private Form editForm;
    private TextField tfProfileName;
    private TextField tfNick;
    private TextField tfAltNick;
    private TextField tfChannel;
    private TextField tfWifi;
    private TextField tfServer;
    private TextField tfPort;
    private TextField tfPassword;
    private Command cmdEditSave;
    private Command cmdEditCancel;
    private int editingIndex = -1; // -1 = new profile

    // --- UI: Chat ---
    private ChatCanvas chatCanvas;
    private TextBox inputBox;
    private TextBox msgTargetBox;
    private TextBox nickBox;
    private Command cmdSend;
    private Command cmdLeave;
    private Command cmdClear;
    private Command cmdTabs;
    private Command cmdUsers;
    private Command cmdNick;
    private Command cmdNewMessage;
    private Command cmdInputOk;
    private Command cmdInputCancel;
    private Command cmdMsgTargetOk;
    private Command cmdMsgTargetCancel;
    private Command cmdNickOk;
    private Command cmdNickCancel;

    // --- UI: Tabs ---
    private List tabsList;
    private Command cmdtabsBack;
    private Command cmdtabsOpen;
    private Command cmdtabsDelete;

    // --- UI: Users ---
    private List usersList;
    private Command cmdUlMessage;
    private Command cmdUlBack;

    // =========================================================
    // Constructor
    // =========================================================

    public IRCClient(IRCMidlet midlet) {
        this.midlet = midlet;
        loadProfiles();
        buildMainForm();
        chatCanvas = new ChatCanvas();
    }

    // =========================================================
    // RMS — Profiles
    // =========================================================

    // Profile record format: name|nick|altNick|channel|wifi|server|port|password
    // Record IDs in RMS are 1-based; we store profile i at record (i+1).

    private void loadProfiles() {
        profiles.removeAllElements();
        activeProfile = 0;

        // Load active index
        try {
            RecordStore rs = RecordStore.openRecordStore(RMS_ACTIVE, false);
            if (rs.getNumRecords() > 0) {
                String s = new String(rs.getRecord(1)).trim();
                try {
                    activeProfile = Integer.parseInt(s);
                } catch (Exception e) {
                }
            }
            rs.closeRecordStore();
        } catch (Exception e) {
        }

        // Load profile records
        try {
            RecordStore rs = RecordStore.openRecordStore(RMS_PROFILES, false);
            int n = rs.getNumRecords();
            for (int id = 1; id <= n; id++) {
                try {
                    byte[] b = rs.getRecord(id);
                    if (b != null) {
                        String[] p = parseProfile(new String(b));
                        profiles.addElement(p);
                    }
                } catch (Exception e) {
                }
            }
            rs.closeRecordStore();
        } catch (Exception e) {
        }

        // Clamp activeProfile
        if (activeProfile >= profiles.size())
            activeProfile = 0;

        // If no profiles, create a default one
        if (profiles.isEmpty()) {
            String[] def = new String[] {
                    "Default", "BBUser", "BBUser_",
                    "#libera", "N", DEFAULT_HOST,
                    String.valueOf(DEFAULT_PORT), ""
            };
            profiles.addElement(def);
            saveAllProfiles();
            saveActiveIndex();
        }
    }

    private String[] parseProfile(String data) {
        String[] p = new String[8];
        for (int i = 0; i < p.length; i++)
            p[i] = "";
        int count = 0, start = 0;
        for (int i = 0; i <= data.length() && count < 7; i++) {
            if (i == data.length() || data.charAt(i) == '|') {
                p[count++] = data.substring(start, i);
                start = i + 1;
            }
        }
        // last field (password) = remainder
        if (count == 7 && start <= data.length()) {
            p[7] = data.substring(start);
        }
        return p;
    }

    private String profileToString(String[] p) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < 7; i++) {
            sb.append(p[i] == null ? "" : p[i]);
            sb.append('|');
        }
        sb.append(p[7] == null ? "" : p[7]);
        return sb.toString();
    }

    private void saveAllProfiles() {
        try {
            // Delete and recreate to avoid stale records
            try {
                RecordStore.deleteRecordStore(RMS_PROFILES);
            } catch (Exception e) {
            }
            RecordStore rs = RecordStore.openRecordStore(RMS_PROFILES, true);
            for (int i = 0; i < profiles.size(); i++) {
                String[] p = (String[]) profiles.elementAt(i);
                byte[] b = profileToString(p).getBytes();
                rs.addRecord(b, 0, b.length);
            }
            rs.closeRecordStore();
        } catch (Exception e) {
        }
    }

    private void saveActiveIndex() {
        try {
            RecordStore rs = RecordStore.openRecordStore(RMS_ACTIVE, true);
            byte[] b = String.valueOf(activeProfile).getBytes();
            if (rs.getNumRecords() == 0) {
                rs.addRecord(b, 0, b.length);
            } else {
                rs.setRecord(1, b, 0, b.length);
            }
            rs.closeRecordStore();
        } catch (Exception e) {
        }
    }

    // =========================================================
    // Main Form
    // =========================================================

    private void buildMainForm() {
        mainForm = new Form("BBIRC v" + midlet.getAppProperty("MIDlet-Version"));

        siProfileName = new StringItem("Profile:", getActiveProfileDisplay());
        mainForm.append(siProfileName);
        mainForm.append(new StringItem("", "")); // spacer
        mainForm.append(new StringItem("", "")); // spacer
        mainForm.append(new StringItem("Credits:", "Made by AzizBgBoss"));
        mainForm.append(new StringItem("GitHub:", "github.com/AzizBgBoss/BBIRC"));

        cmdMainConnect = new Command("Connect", Command.OK, 1);
        cmdMainProfiles = new Command("Profiles", Command.SCREEN, 2);
        cmdMainQuit = new Command("Quit", Command.EXIT, 3);
        mainForm.addCommand(cmdMainConnect);
        mainForm.addCommand(cmdMainProfiles);
        mainForm.addCommand(cmdMainQuit);
        mainForm.setCommandListener(this);
    }

    private String getActiveProfileDisplay() {
        if (profiles.isEmpty())
            return "(none)";
        String[] p = (String[]) profiles.elementAt(activeProfile);
        return p[0] + "  (" + p[1] + " @ " + p[3] + ")";
    }

    private void refreshMainForm() {
        siProfileName.setText(getActiveProfileDisplay());
    }

    // =========================================================
    // Profile List Screen
    // =========================================================

    private void buildProfileList() {
        profileList = new List("Profiles", List.IMPLICIT);
        for (int i = 0; i < profiles.size(); i++) {
            String[] p = (String[]) profiles.elementAt(i);
            String marker = (i == activeProfile) ? "* " : "  ";
            profileList.append(marker + p[0], null);
        }

        cmdPlSelect = new Command("Select", Command.OK, 1);
        cmdPlNew = new Command("New", Command.SCREEN, 2);
        cmdPlEdit = new Command("Edit", Command.SCREEN, 3);
        cmdPlDelete = new Command("Delete", Command.SCREEN, 4);
        cmdPlBack = new Command("Back", Command.BACK, 5);
        cmdPlDuplicate = new Command("Duplicate", Command.SCREEN, 6);

        profileList.addCommand(cmdPlSelect);
        profileList.addCommand(cmdPlNew);
        profileList.addCommand(cmdPlEdit);
        profileList.addCommand(cmdPlDelete);
        profileList.addCommand(cmdPlBack);
        profileList.addCommand(cmdPlDuplicate);
        profileList.setCommandListener(this);
        // also fire SELECT command on implicit list tap
        profileList.setSelectCommand(cmdPlSelect);
    }

    // =========================================================
    // Profile Edit Form
    // =========================================================

    private void buildEditForm(int index) {
        editingIndex = index;
        String[] p;
        String title;

        if (index == -1) {
            // New profile — blank defaults
            p = new String[] { "", "BBUser", "BBUser_", "#libera", "N",
                    DEFAULT_HOST, String.valueOf(DEFAULT_PORT), "" };
            title = "New Profile";
        } else {
            p = (String[]) profiles.elementAt(index);
            title = "Edit: " + p[0];
        }

        editForm = new Form(title);
        tfProfileName = new TextField("Profile Name:", p[0], 32, TextField.ANY);
        tfNick = new TextField("Nickname:", p[1], 32, TextField.ANY);
        tfAltNick = new TextField("Alt Nick:", p[2], 32, TextField.ANY);
        tfChannel = new TextField("Channel:", p[3], 64, TextField.ANY);
        tfWifi = new TextField("Force Wi-Fi (Y/N):", p[4], 1, TextField.ANY);
        tfServer = new TextField("Server:", p[5], 64, TextField.ANY);
        tfPort = new TextField("Port:", p[6], 5, TextField.NUMERIC);
        tfPassword = new TextField("NickServ Pass:", p[7], 64, TextField.PASSWORD);

        editForm.append(tfProfileName);
        editForm.append(tfNick);
        editForm.append(tfAltNick);
        editForm.append(tfChannel);
        editForm.append(tfWifi);
        editForm.append(tfServer);
        editForm.append(tfPort);
        editForm.append(tfPassword);

        cmdEditSave = new Command("Save", Command.OK, 1);
        cmdEditCancel = new Command("Cancel", Command.BACK, 2);
        editForm.addCommand(cmdEditSave);
        editForm.addCommand(cmdEditCancel);
        editForm.setCommandListener(this);
    }

    private void saveEditForm() {
        String name = tfProfileName.getString().trim();
        if (name.length() == 0)
            name = "Profile " + (profiles.size() + 1);

        String ch = tfChannel.getString().trim();
        if (ch.length() > 0 && !ch.startsWith("#"))
            ch = "#" + ch;

        String[] p = new String[] {
                name,
                tfNick.getString().trim(),
                tfAltNick.getString().trim(),
                ch,
                tfWifi.getString().trim().toUpperCase(),
                tfServer.getString().trim(),
                tfPort.getString().trim(),
                tfPassword.getString().trim()
        };
        if (p[1].length() == 0)
            p[1] = "BBUser";
        if (p[5].length() == 0)
            p[5] = DEFAULT_HOST;
        if (p[6].length() == 0)
            p[6] = String.valueOf(DEFAULT_PORT);

        if (editingIndex == -1) {
            profiles.addElement(p);
        } else {
            profiles.setElementAt(p, editingIndex);
        }
        saveAllProfiles();
    }

    // =========================================================
    // Connect using active profile
    // =========================================================

    private void connect() {
        if (connected || connecting) {
            if (connected) {
                midlet.getDisplay().setCurrent(chatCanvas);
            } else {
                showAlert("Please wait", "Already connecting...", mainForm);
            }
            return;
        }

        if (profiles.isEmpty()) {
            showAlert("Error", "No profiles. Create one first.", mainForm);
            return;
        }

        String[] p = (String[]) profiles.elementAt(activeProfile);
        nick = p[1].trim();
        altNick = p[2].trim();
        String channel = p[3].trim();
        nsPassword = p[7].trim();

        if (nick.length() == 0) {
            showAlert("Error", "Profile has no nickname.", mainForm);
            return;
        }
        if (channel.length() == 0) {
            showAlert("Error", "Profile has no channel.", mainForm);
            return;
        }
        if (!channel.startsWith("#"))
            channel = "#" + channel;

        server = p[5].trim();
        if (server.length() == 0)
            server = DEFAULT_HOST;

        try {
            port = Integer.parseInt(p[6].trim());
        } catch (Exception e) {
            port = DEFAULT_PORT;
        }
        if (port <= 0)
            port = DEFAULT_PORT;

        forceWifi = "Y".equals(p[4].trim().toUpperCase());

        buildChatCanvas(channel);
        activeTab = channel;
        chatCanvas.setTitle("Connecting...");
        addMessage("", "* Connecting to " + server + ":" + port + " as " + nick, MSG_SYSTEM);
        midlet.getDisplay().setCurrent(chatCanvas);

        connecting = true;
        final String finalChannel = channel;

        new Thread(new Runnable() {
            public void run() {
                try {
                    socket = (SocketConnection) Connector.open(
                            "socket://" + server + ":" + port +
                                    (forceWifi ? ";interface=wifi" : ""));
                    in = socket.openInputStream();
                    out = socket.openOutputStream();

                    connected = true;
                    connecting = false;
                    registered = false;
                    pendingChannel = finalChannel;

                    sendRaw("NICK " + nick);
                    sendRaw("USER " + nick + " 0 * :" + nick);

                    running = true;
                    readThread = new Thread(IRCClient.this);
                    readThread.start();

                } catch (Exception e) {
                    connecting = false;
                    showAlert("Error", "Could not connect: " + e.getMessage(), mainForm);
                }
            }
        }).start();
    }

    // =========================================================
    // Disconnect
    // =========================================================

    public void disconnect() {
        namesBuffer.setLength(0);
        running = false;
        connecting = false;
        try {
            if (connected && out != null)
                sendRaw("QUIT :BBIRC");
        } catch (Exception e) {
        }
        connected = false;
        registered = false;
        pendingChannel = null;
        closeIO();
    }

    private void closeIO() {
        try {
            if (in != null)
                in.close();
        } catch (Exception e) {
        }
        try {
            if (out != null)
                out.close();
        } catch (Exception e) {
        }
        try {
            if (socket != null)
                socket.close();
        } catch (Exception e) {
        }
        in = null;
        out = null;
        socket = null;
    }

    // =========================================================
    // Send
    // =========================================================

    private boolean sendRaw(String line) {
        try {
            if (!connected || out == null)
                return false;
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
        namesBuffer.setLength(0);
        currentChannel = channel;
        sendRaw("JOIN " + channel);
        activeTab = channel;
        midlet.getDisplay().callSerially(new Runnable() {
            public void run() {
                buildChatCanvas(channel);
                midlet.getDisplay().setCurrent(chatCanvas);
            }
        });
    }

    // =========================================================
    // Read loop
    // =========================================================

    public void run() {
        while (running) {
            StringBuffer lineBuf = new StringBuffer();
            try {
                int lastMin = (int) ((System.currentTimeMillis() / 60000) % 60);
                while (running) {
                    int b = in.read();
                    if (b == -1)
                        break;
                    char c = (char) b;
                    if (c == '\n') {
                        String line = lineBuf.toString().trim();
                        lineBuf.setLength(0);
                        if (line.length() > 0)
                            handleLine(line);
                    } else if (c != '\r') {
                        lineBuf.append(c);
                    }
                    if ((int) ((System.currentTimeMillis() / 60000) % 60) != lastMin) {
                        lastMin = (int) ((System.currentTimeMillis() / 60000) % 60);
                        if (chatCanvas != null)
                            chatCanvas.repaint();
                    }
                }
            } catch (Exception e) {
                if (!running)
                    break; // intentional disconnect, stop
                // network error — attempt reconnect
                addMessage("", "* Connection lost, reconnecting...", MSG_SYSTEM);
                closeIO();
                connected = false;
                registered = false;

                // wait 3 seconds
                try {
                    Thread.sleep(3000);
                } catch (Exception ignored) {
                }

                if (!running)
                    break; // user disconnected while waiting

                // attempt to reopen socket
                try {
                    socket = (SocketConnection) Connector.open(
                            "socket://" + server + ":" + port +
                                    (forceWifi ? ";interface=wifi" : ""));
                    in = socket.openInputStream();
                    out = socket.openOutputStream();

                    connected = true;
                    registered = false;

                    sendRaw("NICK " + nick);
                    sendRaw("USER " + nick + " 0 * :" + nick);
                    addMessage("", "* Reconnected, rejoining...", MSG_SYSTEM);
                    // 001 handler will call joinChannel when registered
                    pendingChannel = currentChannel;

                } catch (Exception re) {
                    addMessage("", "* Reconnect failed: " + re.getMessage(), MSG_SYSTEM);
                    running = false;
                    connected = false;
                    break;
                }
            }
        }

        running = false;
        connected = false;
        registered = false;
        closeIO();
    }

    private void buildPmsList() {
        tabsList = new List("Tabs", List.IMPLICIT);
        tabsList.append(currentChannel, null);
        for (int i = 0; i < privateTabs.size(); i++)
            tabsList.append((String) privateTabs.elementAt(i), null);
        cmdtabsOpen = new Command("Open", Command.OK, 1);
        cmdtabsBack = new Command("Back", Command.BACK, 2);
        cmdtabsDelete = new Command("Delete", Command.SCREEN, 3);

        tabsList.addCommand(cmdtabsOpen);
        tabsList.addCommand(cmdtabsBack);
        tabsList.addCommand(cmdtabsDelete);
        tabsList.setCommandListener(this);
        tabsList.setSelectCommand(cmdtabsOpen);
    }

    private void buildUsersList() {
        usersList = new List(String.valueOf(nicks.size()) + " users in " + currentChannel, List.IMPLICIT);
        for (int i = 0; i < nicks.size(); i++)
            usersList.append((String) nicks.elementAt(i), null);
        cmdUlMessage = new Command("Message", Command.OK, 1);
        cmdUlBack = new Command("Back", Command.BACK, 2);

        usersList.addCommand(cmdUlMessage);
        usersList.addCommand(cmdUlBack);
        usersList.setCommandListener(this);
        usersList.setSelectCommand(cmdUlMessage);
    }

    public boolean contains(Vector v, String s) {
        for (int i = 0; i < v.size(); i++) {
            if (((String) v.elementAt(i)).equals(s))
                return true;
        }
        return false;
    }

    public int indexOf(Vector v, String s) {
        for (int i = 0; i < v.size(); i++) {
            if (((String) v.elementAt(i)).equals(s))
                return i;
        }
        return -1;
    }

    private boolean isChannelTab() {
        return activeTab != null && activeTab.startsWith("#");
    }

    // =========================================================
    // IRC Protocol
    // =========================================================

    private void handleLine(String line) {
        if (line.startsWith("PING")) {
            sendRaw("PONG " + line.substring(5));
            return;
        }

        String prefix = "";
        String command = "";
        String params = "";
        String rest = line;

        if (rest.startsWith(":")) {
            int sp = rest.indexOf(' ');
            if (sp != -1) {
                prefix = rest.substring(1, sp);
                rest = rest.substring(sp + 1).trim();
            }
        }

        int sp = rest.indexOf(' ');
        if (sp != -1) {
            command = rest.substring(0, sp);
            params = rest.substring(sp + 1).trim();
        } else {
            command = rest;
        }

        String senderNick = prefix;
        int excl = prefix.indexOf('!');
        if (excl != -1)
            senderNick = prefix.substring(0, excl);

        if (command.equals("PRIVMSG")) {
            int spaceColon = params.indexOf(" :");
            if (spaceColon != -1) {
                String target = params.substring(0, spaceColon).trim();
                String message = params.substring(spaceColon + 2);
                if (target.equals(currentChannel)) {
                    addMessage(senderNick, message, MSG_OTHER);
                } else if (!target.startsWith("#")) { // looks like we are getting a private message!
                    int tabID = -1;
                    if (contains(privateTabs, senderNick)) {
                        tabID = indexOf(privateTabs, senderNick);
                    } else {
                        if (privateTabs.size() < MAX_PRIVATE_TABS) {
                            privateTabs.addElement(senderNick);
                        } else {
                            for (int i = 0; i < privateTabs.size() - 1; i++) {
                                if (!privateTabs.elementAt(i).equals(senderNick)) {
                                    privateTabs.removeElementAt(i);
                                    break;
                                }
                            }
                            privateTabs.addElement(senderNick);
                        }
                        tabID = privateTabs.size() - 1;
                        privateMessages[tabID] = new Vector();
                        privateTimestamps[tabID] = new Vector();
                    }

                    if (tabID != -1 && privateMessages[tabID] != null) // just to be safe
                    {
                        addPMessage(tabID, senderNick, message, MSG_OTHER);
                    }
                }
            }
        } else if (command.equals("JOIN")) {
            if (senderNick.equals(nick)) {
                // server confirms the real channel name
                int colon = params.indexOf(':');
                String confirmedChannel = colon != -1 ? params.substring(colon + 1).trim() : params.trim();
                if (confirmedChannel.length() > 0) {
                    currentChannel = confirmedChannel;
                    activeTab = confirmedChannel;
                    midlet.getDisplay().callSerially(new Runnable() {
                        public void run() {
                            chatCanvas.setTitle(currentChannel);
                            chatCanvas.repaint();
                        }
                    });
                }
            } else {
                nicks.addElement(senderNick);
                addMessage("", "* " + senderNick + " joined", MSG_SYSTEM);
            }
        } else if (command.equals("PART")) {
            if (nicks != null && nicks.contains(senderNick)) // Too safe than sorry
                nicks.removeElement(senderNick);
            addMessage("", "* " + senderNick + " left", MSG_SYSTEM);
        } else if (command.equals("QUIT")) {
            if (nicks != null && nicks.contains(senderNick)) // Too safe than sorry
                nicks.removeElement(senderNick);
            addMessage("", "* " + senderNick + " quit", MSG_SYSTEM);
        } else if (command.equals("NICK")) {
            if (senderNick.equals(nick)) {
                int c2 = params.indexOf(':');
                nick = c2 != -1 ? params.substring(c2 + 1) : params;
                if (nicks != null && nicks.contains(senderNick)) {
                    nicks.removeElement(senderNick);
                    nicks.addElement(nick);
                }
                addMessage("", "* You are now known as " + nick, MSG_SYSTEM);
            } else {
                int c2 = params.indexOf(':');
                String newNick = c2 != -1 ? params.substring(c2 + 1) : params;
                if (nicks != null && nicks.contains(senderNick)) {
                    nicks.removeElement(senderNick);
                    nicks.addElement(newNick);
                }
                addMessage("", "* " + senderNick + " is now " + newNick, MSG_SYSTEM);
            }
        } else if (command.equals("353")) {
            int colon = params.lastIndexOf(':');
            if (colon != -1) {
                String chunk = params.substring(colon + 1).trim();
                if (namesBuffer.length() > 0)
                    namesBuffer.append(' ');
                namesBuffer.append(chunk);
            }
        } else if (command.equals("366")) {
            String names = namesBuffer.toString().trim();
            namesBuffer.setLength(0);
            if (names.length() == 0)
                return;

            nicks = new Vector();
            int s = 0;
            for (int i = 0; i <= names.length(); i++) {
                if (i == names.length() || names.charAt(i) == ' ') {
                    if (i > s)
                        nicks.addElement(names.substring(s, i));
                    s = i + 1;
                }
            }
            int total = nicks.size();
            int show = total > 10 ? 10 : total;
            StringBuffer sb = new StringBuffer("* Users: ");
            for (int i = 0; i < show; i++) {
                if (i > 0)
                    sb.append(' ');
                sb.append((String) nicks.elementAt(i));
            }
            if (total > 10)
                sb.append(" +").append(total - 10).append(" more");
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

    // =========================================================
    // Notifications / Errors
    // =========================================================

    private void notification(final int duration, final String msg) {
        try {
            midlet.getDisplay().vibrate(duration);
            if (msg != null)
                notificationMsg = msg;
            if (chatCanvas != null) {
                midlet.getDisplay().callSerially(new Runnable() {
                    public void run() {
                        if (chatCanvas != null)
                            chatCanvas.repaint();
                    }
                });
            }
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(3000);
                    } catch (Exception e) {
                    }
                    synchronized (IRCClient.this) {
                        if (msg != null && msg.equals(notificationMsg))
                            notificationMsg = null;
                    }
                    if (chatCanvas != null) {
                        midlet.getDisplay().callSerially(new Runnable() {
                            public void run() {
                                if (chatCanvas != null)
                                    chatCanvas.repaint();
                            }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
        }
    }

    private void handleIOError(Exception e) {
        connected = false;
        closeIO();
    }

    // =========================================================
    // Messages
    // =========================================================

    private synchronized void addMessage(final String msgNick,
            final String text,
            final int type) {
        long now = System.currentTimeMillis();
        int hours = (int) ((now / 3600000) % 24);
        int minutes = (int) ((now / 60000) % 60);
        String ts = pad(hours) + ":" + pad(minutes);

        if (messages.size() >= MAX_MESSAGES) {
            messages.removeElementAt(0);
            timestamps.removeElementAt(0);
        }

        if (type == MSG_OTHER) {
            if (activeTab != null && activeTab.equals(currentChannel))
                notification(100, null);
            else
                notification(200, "New message in " + currentChannel);
        }

        messages.addElement(new String[] { msgNick, text, String.valueOf(type) });
        timestamps.addElement(ts);

        if (isChannelTab()) // we are in a channel, u can repaint
            midlet.getDisplay().callSerially(new Runnable() {
                public void run() {
                    if (chatCanvas != null) {
                        chatCanvas.resetScroll();
                        chatCanvas.repaint();
                    }
                }
            });
    }

    private synchronized void addPMessage(final int ID,
            final String senderNick,
            final String text,
            final int type) {
        long now = System.currentTimeMillis();
        int hours = (int) ((now / 3600000) % 24);
        int minutes = (int) ((now / 60000) % 60);
        String ts = pad(hours) + ":" + pad(minutes);

        if (privateMessages[ID].size() >= MAX_MESSAGES) {
            privateMessages[ID].removeElementAt(0);
            privateTimestamps[ID].removeElementAt(0);
        }

        if (type == MSG_OTHER) {
            if (activeTab != null && activeTab.equals(privateTabs.elementAt(ID)))
                notification(100, null);
            else
                notification(200, "New private message from " + senderNick);
        }

        privateMessages[ID].addElement(new String[] { senderNick, text, String.valueOf(type) });
        privateTimestamps[ID].addElement(ts);

        if (!isChannelTab() && activeTab != null && activeTab.equals(privateTabs.elementAt(ID))) // we are in a private
                                                                                                 // convo, das diddy
                                                                                                 // bluden
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

    // =========================================================
    // Chat Canvas builder
    // =========================================================

    private void buildChatCanvas(String channel) {
        chatCanvas = new ChatCanvas();
        chatCanvas.setTitle(channel);

        cmdSend = new Command("Send", Command.OK, 1);
        cmdLeave = new Command("Leave", Command.EXIT, 2);
        cmdClear = new Command("Clear", Command.SCREEN, 3);
        cmdTabs = new Command("Tabs", Command.SCREEN, 4);
        cmdUsers = new Command("Users", Command.SCREEN, 5);
        cmdNewMessage = new Command("New Message", Command.SCREEN, 6);
        cmdNick = new Command("Change Nick", Command.SCREEN, 7);
        chatCanvas.addCommand(cmdSend);
        chatCanvas.addCommand(cmdLeave);
        chatCanvas.addCommand(cmdClear);
        chatCanvas.addCommand(cmdTabs);
        chatCanvas.addCommand(cmdUsers);
        chatCanvas.addCommand(cmdNewMessage);
        chatCanvas.addCommand(cmdNick);
        chatCanvas.setCommandListener(this);
    }

    // =========================================================
    // CommandListener
    // =========================================================

    public void commandAction(Command c, Displayable d) {

        // --- Main form ---
        if (d == mainForm) {
            if (c == cmdMainConnect) {
                connect();
            } else if (c == cmdMainProfiles) {
                buildProfileList();
                midlet.getDisplay().setCurrent(profileList);
            } else if (c == cmdMainQuit) {
                disconnect();
                midlet.quit();
            }
        }

        // --- Profile list ---
        else if (d == profileList) {
            int idx = profileList.getSelectedIndex();
            if (c == cmdPlSelect) {
                if (idx >= 0) {
                    activeProfile = idx;
                    saveActiveIndex();
                    refreshMainForm();
                }
                midlet.getDisplay().setCurrent(mainForm);
            } else if (c == cmdPlNew) {
                if (profiles.size() >= MAX_PROFILES) {
                    showAlert("Limit", "Max " + MAX_PROFILES + " profiles.", profileList);
                } else {
                    buildEditForm(-1);
                    midlet.getDisplay().setCurrent(editForm);
                }
            } else if (c == cmdPlEdit) {
                if (idx >= 0) {
                    buildEditForm(idx);
                    midlet.getDisplay().setCurrent(editForm);
                } else {
                    showAlert("Edit", "Select a profile first.", profileList);
                }
            } else if (c == cmdPlDelete) {
                if (profiles.size() <= 1) {
                    showAlert("Delete", "Cannot delete the only profile.", profileList);
                } else if (idx >= 0) {
                    profiles.removeElementAt(idx);
                    if (activeProfile >= profiles.size())
                        activeProfile = profiles.size() - 1;
                    saveAllProfiles();
                    saveActiveIndex();
                    // Rebuild list in place
                    buildProfileList();
                    midlet.getDisplay().setCurrent(profileList);
                }
            } else if (c == cmdPlBack) {
                refreshMainForm();
                midlet.getDisplay().setCurrent(mainForm);
            } else if (c == cmdPlDuplicate) {
                if (idx >= 0) {
                    if (profiles.size() < MAX_PROFILES) {
                        String[] orig = (String[]) profiles.elementAt(idx);
                        String[] copy = new String[orig.length];
                        for (int i = 0; i < orig.length; i++)
                            copy[i] = orig[i];
                        profiles.addElement(copy);
                        saveAllProfiles();
                        buildProfileList();
                        midlet.getDisplay().setCurrent(profileList);
                    } else {
                        showAlert("Limit", "Max " + MAX_PROFILES + " profiles.", profileList);
                    }
                }
            }
        }

        // --- Edit form ---
        else if (d == editForm) {
            if (c == cmdEditSave) {
                saveEditForm();
                // If we just edited the active profile, refresh main form label
                buildProfileList();
                refreshMainForm();
                midlet.getDisplay().setCurrent(profileList);
            } else if (c == cmdEditCancel) {
                midlet.getDisplay().setCurrent(profileList);
            }
        }

        // --- Chat canvas ---
        else if (d == chatCanvas) {
            if (c == cmdSend) {
                inputBox = new TextBox("Message", "", 256, TextField.ANY);
                cmdInputOk = new Command("Send", Command.OK, 1);
                cmdInputCancel = new Command("Cancel", Command.BACK, 2);
                inputBox.addCommand(cmdInputOk);
                inputBox.addCommand(cmdInputCancel);
                inputBox.setCommandListener(this);
                midlet.getDisplay().setCurrent(inputBox);
            } else if (c == cmdLeave) {
                sendRaw("PART " + currentChannel + " :leaving");
                disconnect();
                midlet.getDisplay().setCurrent(mainForm);
            } else if (c == cmdClear) {
                synchronized (IRCClient.this) {
                    messages.removeAllElements();
                    timestamps.removeAllElements();
                }
                if (chatCanvas != null) {
                    chatCanvas.resetScroll();
                    chatCanvas.repaint();
                }
            } else if (c == cmdTabs) {
                if (privateTabs.isEmpty())
                    showAlert("", "No other messages!", chatCanvas);
                else {
                    buildPmsList();
                    midlet.getDisplay().setCurrent(tabsList);
                }
            } else if (c == cmdUsers) {
                if (nicks == null) {
                    showAlert("", "Please wait until you are connected to the server.", chatCanvas);
                } else {
                    buildUsersList();
                    midlet.getDisplay().setCurrent(usersList);
                }
            } else if (c == cmdNewMessage) {
                if (!connected) {
                    showAlert("", "Please wait until you are connected to the server.", chatCanvas);
                } else {
                    msgTargetBox = new TextBox("Private message to", "", 32, TextField.ANY);
                    cmdMsgTargetOk = new Command("PM", Command.OK, 1);
                    cmdMsgTargetCancel = new Command("Cancel", Command.BACK, 2);

                    msgTargetBox.addCommand(cmdMsgTargetOk);
                    msgTargetBox.addCommand(cmdMsgTargetCancel);
                    msgTargetBox.setCommandListener(this);
                    midlet.getDisplay().setCurrent(msgTargetBox);
                }
            } else if (c == cmdNick) {
                if (!connected) {
                    showAlert("", "Please wait until you are connected to the server.", chatCanvas);
                } else {
                    nickBox = new TextBox("Change nickname", nick, 32, TextField.ANY);
                    cmdNickOk = new Command("Change", Command.OK, 1);
                    cmdNickCancel = new Command("Cancel", Command.BACK, 2);

                    nickBox.addCommand(cmdNickOk);
                    nickBox.addCommand(cmdNickCancel);
                    nickBox.setCommandListener(this);
                    midlet.getDisplay().setCurrent(nickBox);
                }
            }
        }

        // --- Change Nick ---
        else if (d == nickBox) {
            if (c.getCommandType() == Command.OK) {
                String newNick = nickBox.getString().trim();
                if (newNick.length() > 0) {
                    if (!connected) {
                        showAlert("", "Please wait until you are connected to the server.", chatCanvas);
                    } else {
                        if (!newNick.equals(nick)) {
                            sendRaw("NICK " + newNick);
                            midlet.getDisplay().setCurrent(chatCanvas);
                        } else {
                            showAlert("", "Please set a different nickname.", nickBox);
                        }
                    }
                }
            } else {
                midlet.getDisplay().setCurrent(chatCanvas);
            }
        }

        // --- PM target ---
        else if (d == msgTargetBox) {
            if (c.getCommandType() == Command.OK) {
                String target = msgTargetBox.getString().trim().replace('\n', '_').replace(' ', '_');
                if (target.length() > 0) {
                    if (target.equals(nick)) {
                        showAlert("Error", "Can't message yourself!", msgTargetBox);
                    } else {
                        int tabID = -1;
                        if (contains(privateTabs, target)) {
                            tabID = indexOf(privateTabs, target);
                        } else {
                            if (privateTabs.size() < MAX_PRIVATE_TABS) {
                                privateTabs.addElement(target);
                            } else {
                                for (int i = 0; i < privateTabs.size() - 1; i++) {
                                    if (!privateTabs.elementAt(i).equals(target)) {
                                        privateTabs.removeElementAt(i);
                                        break;
                                    }
                                }
                                privateTabs.addElement(target);
                            }
                            tabID = privateTabs.size() - 1;
                            privateMessages[tabID] = new Vector();
                            privateTimestamps[tabID] = new Vector();
                        }
                        activeTab = target;
                        chatCanvas.setTitle(activeTab);
                        chatCanvas.resetScroll();
                        midlet.getDisplay().setCurrent(chatCanvas);
                    }
                } else {
                    midlet.getDisplay().setCurrent(chatCanvas);
                }
            } else {
                midlet.getDisplay().setCurrent(chatCanvas);
            }
        }

        // --- Input box ---
        else if (d == inputBox) {
            if (c.getCommandType() == Command.OK) {
                String msg = inputBox.getString().trim().replace('\n', ' ');
                if (msg.length() > 0) {
                    if (activeTab != null && !activeTab.startsWith("#")) {
                        boolean ok = sendRaw("PRIVMSG " + activeTab + " :" + msg);
                        if (ok) {
                            int tabID = indexOf(privateTabs, activeTab);
                            if (tabID != -1)
                                addPMessage(tabID, nick, msg, MSG_SELF);
                        }
                    } else {
                        sendMessage(currentChannel, msg);
                    }
                }
                midlet.getDisplay().setCurrent(chatCanvas);
            } else {
                midlet.getDisplay().setCurrent(chatCanvas);
            }
        }

        // --- Tabs ---
        else if (d == tabsList) {
            if (c == cmdtabsOpen) {
                int idx = tabsList.getSelectedIndex();
                if (idx == 0) {
                    activeTab = currentChannel;
                    chatCanvas.setTitle(activeTab);
                    chatCanvas.resetScroll();
                    midlet.getDisplay().setCurrent(chatCanvas);
                } else if (idx >= 1) {
                    activeTab = (String) privateTabs.elementAt(idx - 1);
                    chatCanvas.setTitle(activeTab);
                    chatCanvas.resetScroll();
                    midlet.getDisplay().setCurrent(chatCanvas);
                }
            } else if (c == cmdtabsDelete) {
                int idx = tabsList.getSelectedIndex();
                if (idx == 0) {
                    showAlert("Error!",
                            "Can't delete connected channel! If you want to quit please open the channel then select Close in options.",
                            tabsList);
                } else if (idx >= 1) {
                    if (privateTabs.elementAt(idx - 1).equals(activeTab)) {
                        activeTab = currentChannel;
                        chatCanvas.setTitle(activeTab);
                        chatCanvas.resetScroll();
                    }
                    privateTabs.removeElementAt(idx - 1);

                    // shift privateMessages and privateTimestamps down
                    for (int i = idx - 1; i < MAX_PRIVATE_TABS - 1; i++) {
                        privateMessages[i] = privateMessages[i + 1];
                        privateTimestamps[i] = privateTimestamps[i + 1];
                    }
                    privateMessages[MAX_PRIVATE_TABS - 1] = null;
                    privateTimestamps[MAX_PRIVATE_TABS - 1] = null;

                    buildPmsList();
                    midlet.getDisplay().setCurrent(tabsList);
                }
            } else if (c == cmdtabsBack) {
                midlet.getDisplay().setCurrent(chatCanvas);
            }
        }

        // --- Users ---
        else if (d == usersList) {
            if (c == cmdUlMessage) {
                String senderNick = (String) nicks.elementAt(usersList.getSelectedIndex());
                if (senderNick.length() > 0) {
                    char first = senderNick.charAt(0);
                    if (first == '@' || first == '+' || first == '%' || first == '~' || first == '&')
                        senderNick = senderNick.substring(1);
                }
                int tabID = -1;
                if (senderNick.equals(nick)) {
                    showAlert("Error", "Can't message yourself!", usersList);
                } else {
                    if (contains(privateTabs, senderNick)) {
                        tabID = indexOf(privateTabs, senderNick);
                    } else {
                        if (privateTabs.size() < MAX_PRIVATE_TABS) {
                            privateTabs.addElement(senderNick);
                        } else {
                            for (int i = 0; i < privateTabs.size() - 1; i++) {
                                if (!privateTabs.elementAt(i).equals(senderNick)) {
                                    privateTabs.removeElementAt(i);
                                    break;
                                }
                            }
                            privateTabs.addElement(senderNick);
                        }
                        tabID = privateTabs.size() - 1;
                        privateMessages[tabID] = new Vector();
                        privateTimestamps[tabID] = new Vector();
                    }
                    activeTab = senderNick;
                    chatCanvas.setTitle(activeTab);
                    chatCanvas.resetScroll();
                    midlet.getDisplay().setCurrent(chatCanvas);
                }
            } else if (c == cmdUlBack) {
                midlet.getDisplay().setCurrent(chatCanvas);
            }
        }
    }

    // =========================================================
    // Lifecycle
    // =========================================================

    public void goBackground() {
        // do nothing — socket stays open
    }

    public void resume() {
        if (connected && chatCanvas != null) {
            midlet.getDisplay().setCurrent(chatCanvas);
        }
    }

    public Displayable getMainScreen() {
        return mainForm;
    }

    // =========================================================
    // Helpers
    // =========================================================

    private void showAlert(String title, String msg, Displayable next) {
        Alert a = new Alert(title, msg, null, AlertType.INFO);
        a.setTimeout(2000);
        midlet.getDisplay().setCurrent(a, next);
    }

    // =========================================================
    // Chat Canvas
    // =========================================================

    private class ChatCanvas extends Canvas {

        private int[] cachedMsgLines = new int[0];
        private int cachedMsgCount = 0;
        private int cachedWidth = 0;
        private String title = "";
        private int scrollOffset = 0;
        private String activeTabInCanvas = null;

        private Vector chatMessages = new Vector();

        private Vector chatTimestamps = new Vector();

        protected void keyPressed(int keyCode) {
            int action = getGameAction(keyCode);
            if (action == UP) {
                scrollOffset++;
                repaint();
            } else if (action == DOWN) {
                if (scrollOffset > 0)
                    scrollOffset--;
                repaint();
            }
        }

        public void resetScroll() {
            scrollOffset = 0;
            cachedMsgCount = -1; // invalidate cache
        }

        public void setTitle(String t) {
            this.title = t;
        }

        // --- Cache ---

        private void rebuildCache(Font fontSmall, int W) {
            int msgCount = chatMessages.size();
            cachedMsgLines = new int[msgCount];
            synchronized (IRCClient.this) {
                for (int i = 0; i < msgCount; i++) {
                    String[] msg = (String[]) chatMessages.elementAt(i);
                    String text = msg[1];
                    int type = Integer.parseInt(msg[2]);
                    boolean grouped = isGrouped(i);
                    if (type == MSG_SYSTEM) {
                        int tsW = fontSmall.stringWidth("00:00 ");
                        cachedMsgLines[i] = wrapText(text, fontSmall, W - tsW - 2).length;
                    } else if (grouped) {
                        cachedMsgLines[i] = wrapText(text, fontSmall, W - 2).length;
                    } else {
                        String nickPrefix = msg[0] + ": ";
                        int nickW = fontSmall.stringWidth(nickPrefix); // approx, bold slightly wider but close enough
                        int tsW2 = fontSmall.stringWidth("00:00 ");
                        int firstLineW = W - tsW2 - nickW - 2;
                        if (firstLineW < 20)
                            firstLineW = 20;
                        String[] firstWrapped = wrapText(text, fontSmall, firstLineW);
                        String firstLine = firstWrapped[0];
                        String remainder = text.length() > firstLine.length()
                                ? text.substring(firstLine.length()).trim()
                                : "";
                        int contLines = remainder.length() > 0
                                ? wrapText(remainder, fontSmall, W - 2).length
                                : 0;
                        cachedMsgLines[i] = 1 + contLines;
                    }
                }
            }
            cachedMsgCount = msgCount;
            cachedWidth = W;
        }

        // --- Text wrap ---

        private String[] wrapText(String text, Font font, int maxWidth) {

            Vector lines = new Vector();
            while (text.length() > 0) {
                if (font.stringWidth(text) <= maxWidth) {
                    lines.addElement(text);
                    break;
                }
                int cut = text.length() - 1;
                while (cut > 0 && font.stringWidth(text.substring(0, cut)) > maxWidth)
                    cut--;
                int space = text.lastIndexOf(' ', cut);
                if (space > 0)
                    cut = space;
                lines.addElement(text.substring(0, cut));
                text = text.substring(cut).trim();
            }
            if (lines.isEmpty())
                lines.addElement("");
            String[] result = new String[lines.size()];
            for (int i = 0; i < lines.size(); i++)
                result[i] = (String) lines.elementAt(i);
            return result;
        }

        // --- Grouping ---

        private boolean isGrouped(int i) {
            if (i == 0)
                return false;
            String[] cur = (String[]) chatMessages.elementAt(i);
            String[] prev = (String[]) chatMessages.elementAt(i - 1);
            int curType = Integer.parseInt(cur[2]);
            int prevType = Integer.parseInt(prev[2]);
            if (curType == MSG_SYSTEM)
                return false;
            if (prevType == MSG_SYSTEM)
                return false;
            if (!cur[0].equals(prev[0]))
                return false;
            String tsCur = (String) chatTimestamps.elementAt(i);
            String tsPrev = (String) chatTimestamps.elementAt(i - 1);
            int diff = tsToMinutes(tsCur) - tsToMinutes(tsPrev);
            if (diff < 0)
                diff += 1440;
            return diff <= 10;
        }

        private int tsToMinutes(String ts) {
            try {
                return Integer.parseInt(ts.substring(0, 2)) * 60
                        + Integer.parseInt(ts.substring(3, 5));
            } catch (Exception e) {
                return 0;
            }
        }

        // --- Paint ---

        protected void paint(Graphics g) {

            if (activeTabInCanvas == null || !activeTabInCanvas.equals(activeTab)) {
                activeTabInCanvas = activeTab;
                cachedMsgCount = -1; // invalidate cache
                if (isChannelTab()) {
                    synchronized (IRCClient.this) {
                        chatMessages = messages;
                        chatTimestamps = timestamps;
                    }
                } else {
                    int tabID = indexOf(privateTabs, activeTab);
                    if (tabID != -1) {
                        synchronized (IRCClient.this) {
                            chatMessages = privateMessages[tabID];
                            chatTimestamps = privateTimestamps[tabID];
                        }
                    } else {
                        chatMessages = new Vector();
                        chatTimestamps = new Vector();
                    }
                }
            }

            int W = getWidth();
            int H = getHeight();

            g.setColor(COLOR_BG);
            g.fillRect(0, 0, W, H);

            Font fontSmall = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
            Font fontBold = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_SMALL);
            int lineH = fontSmall.getHeight() + 2;

            // Title bar

            long now = System.currentTimeMillis();
            int hours = (int) ((now / 3600000) % 24);
            int minutes = (int) ((now / 60000) % 60);

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
            g.drawString(notificationMsg != null ? notificationMsg : (pad(hours) + ":" + pad(minutes)), W / 2,
                    hintY + 2,
                    Graphics.TOP | Graphics.HCENTER); // Place holder, gonna change it later to notify about pms

            int chatTop = titleH + 2;
            int chatBot = hintY - 2;
            int maxLines = (chatBot - chatTop) / lineH;

            int msgCount = chatMessages.size();
            if (msgCount == 0)
                return;

            // Rebuild cache if needed
            if (msgCount != cachedMsgCount || W != cachedWidth) {
                rebuildCache(fontSmall, W);
            }
            int[] msgLines = cachedMsgLines;

            int totalLines = 0;
            for (int i = 0; i < msgCount; i++)
                totalLines += msgLines[i];

            int maxScroll = totalLines - maxLines;
            if (maxScroll < 0)
                maxScroll = 0;
            if (scrollOffset > maxScroll)
                scrollOffset = maxScroll;
            if (scrollOffset < 0)
                scrollOffset = 0;

            int skipLines = totalLines - maxLines - scrollOffset;
            if (skipLines < 0)
                skipLines = 0;

            int start = 0, skipped = 0;
            while (start < msgCount && skipped + msgLines[start] <= skipLines) {
                skipped += msgLines[start];
                start++;
            }
            int partialSkip = skipLines - skipped;
            int y = chatTop - partialSkip * lineH;

            g.setClip(0, chatTop, W, chatBot - chatTop);

            synchronized (IRCClient.this) {
                for (int i = start; i < msgCount; i++) {
                    String[] msg = (String[]) chatMessages.elementAt(i);
                    String ts = (String) chatTimestamps.elementAt(i);
                    String msgNick = msg[0];
                    String text = msg[1];
                    int type = Integer.parseInt(msg[2]);
                    boolean grouped = isGrouped(i);
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
                        // measure nick prefix to know remaining width for first line
                        int nickColor = Math.abs(msgNick.hashCode()) % 0xAAAAAA + 0x555555;
                        String nickPrefix = msgNick + ": ";
                        int nickW = fontBold.stringWidth(nickPrefix);
                        int firstLineW = W - tsW - nickW - 2;
                        if (firstLineW < 20)
                            firstLineW = 20; // safety

                        // wrap: first line gets less room, continuation lines get full width
                        String[] firstWrapped = wrapText(text, fontSmall, firstLineW);
                        String firstLine = firstWrapped[0];
                        // remaining text after first line
                        String remainder = text.length() > firstLine.length()
                                ? text.substring(firstLine.length()).trim()
                                : "";
                        String[] contWrapped = remainder.length() > 0
                                ? wrapText(remainder, fontSmall, W - 2)
                                : new String[0];

                        // draw: timestamp + nick + first line of text all on same y
                        g.setFont(fontSmall);
                        g.setColor(COLOR_TIMESTAMP);
                        g.drawString(ts + " ", 2, y, Graphics.TOP | Graphics.LEFT);
                        g.setFont(fontBold);
                        g.setColor(nickColor);
                        g.drawString(nickPrefix, tsW, y, Graphics.TOP | Graphics.LEFT);
                        g.setFont(fontSmall);
                        g.setColor(COLOR_TEXT);
                        g.drawString(firstLine, tsW + nickW, y, Graphics.TOP | Graphics.LEFT);
                        y += lineH;

                        // continuation lines flush left
                        for (int w = 0; w < contWrapped.length; w++) {
                            g.setFont(fontSmall);
                            g.setColor(COLOR_TEXT);
                            g.drawString(contWrapped[w], 2, y, Graphics.TOP | Graphics.LEFT);
                            y += lineH;
                        }
                    }

                    if (y >= chatBot)
                        break;
                }
            }

            g.setClip(0, 0, W, H);
        }
    }
}