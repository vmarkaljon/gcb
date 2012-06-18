/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import gcb.bot.ChatThread;
import gcb.bot.SQLThread;
import gcb.plugin.PluginManager;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 *
 * @author wizardus
 */
public class Main {
	public static String VERSION = "gcb 0f";
	public static boolean DEBUG = false;
	public static final String DATE_FORMAT = "yyyy-MM-dd";
	static boolean log;
	static boolean logCommands;
	boolean botDisable;
	boolean reverse;
	long lastLog; //when the log file(s) were created
	static int newLogInterval; //ms interval between creating new log file(s)

	static PrintWriter log_out;
	static PrintWriter log_cmd_out;
	
	GChatBot bot;

	PluginManager plugins;
	GarenaInterface garena;
	WC3Interface wc3i;
	GarenaThread gsp_thread;
	GarenaThread gcrp_thread;
	GarenaThread pl_thread;
	GarenaThread wc3_thread;
	SQLThread sqlthread;
	ChatThread chatthread;
	GarenaReconnect reconnect;

	//determine what will be loaded, what won't be loaded
	boolean loadBot;
	boolean loadPlugins;
	boolean loadWC3;
	boolean loadPL;
	boolean loadSQL;
	boolean loadChat;

	public void init(String[] args) {
		System.out.println(VERSION);
		GCBConfig.load(args);

		//determine what to load based on gcb_reverse and gcb_bot
		loadBot = GCBConfig.configuration.getBoolean("gcb_bot", false);
		botDisable = GCBConfig.configuration.getBoolean("gcb_bot_disable", true);
		reverse = GCBConfig.configuration.getBoolean("gcb_reverse", false);
		log = GCBConfig.configuration.getBoolean("gcb_log", false);
		logCommands = GCBConfig.configuration.getBoolean("gcb_log_commands", false);
		newLogInterval = GCBConfig.configuration.getInt("gcb_log_new_file", 86400000);

		//first load all the defaults
		loadPlugins = true;
		loadWC3 = true;
		loadPL = true;
		loadSQL = false;
		loadChat = false;

		if(loadBot) {
			loadSQL = true;
			loadChat = true;
		}

		if(loadBot && botDisable) {
			loadWC3 = false;
			loadPL = false;
		}

		if(reverse) {
			loadWC3 = false; //or else we'll broadcast our own packets
		}
		
		lastLog = System.currentTimeMillis();
	}

	public void initPlugins() {
		if(loadPlugins) {
			plugins = new PluginManager();
		}
	}

	public void loadPlugins() {
		if(loadPlugins) {
			plugins.setGarena(garena, wc3i, gsp_thread, gcrp_thread, pl_thread, wc3_thread, sqlthread, chatthread);
			plugins.initPlugins();
			plugins.loadPlugins();
		}
	}

	public boolean initGarena(boolean restart) {
		//connect to garena
		if(!restart) {
			garena = new GarenaInterface(plugins);
			reconnect = new GarenaReconnect(this);
			garena.registerListener(reconnect);
		}

		if(!garena.init()) {
			return false;
		}

		if(loadWC3 && !restart) {
			//setup wc3 broadcast reader
			wc3i = new WC3Interface(garena);
			garena.setWC3Interface(wc3i);

			if(!wc3i.init()) {
				return false;
			}
		}

		initPeer();

		if(loadWC3 && (!restart || wc3_thread.terminated)) {
			//start receiving and broadcasting wc3 packets
			wc3_thread = new GarenaThread(garena, wc3i, GarenaThread.WC3_BROADCAST);
			wc3_thread.start();
		}

		//authenticate with login server
		if(!garena.sendGSPSessionInit()) return false;
		if(!garena.readGSPSessionInitReply()) return false;
		if(!garena.sendGSPSessionHello()) return false;
		if(!garena.readGSPSessionHelloReply()) return false;
		if(!garena.sendGSPSessionLogin()) return false;
		if(!garena.readGSPSessionLoginReply()) return false;

		if(!restart || gsp_thread.terminated) {
			gsp_thread = new GarenaThread(garena, wc3i, GarenaThread.GSP_LOOP);
			gsp_thread.start();
		}

		if(loadChat && !restart) {
			chatthread = new ChatThread(garena);
			chatthread.start();
		}

		//make sure we get correct external ip/port; do on restart in case they changed
		lookup();

		return true;
	}
	
	public void initPeer() {
		if(loadPL) {
			//startup GP2PP system
			GarenaThread pl = new GarenaThread(garena, wc3i, GarenaThread.PEER_LOOP);
			pl.start();
		}
	}

	public void lookup() {
		if(loadPL) {
			//lookup
			garena.sendPeerLookup();

			Main.println("[Main] Waiting for lookup response...");
			while(garena.iExternal == null) {
				try {
					Thread.sleep(100);
				} catch(InterruptedException e) {}
			}

			Main.println("[Main] Received lookup response!");
		}
	}

	//returns whether init succeeded; restart=true indicates this isn't the first time we're calling
	public boolean initRoom(boolean restart) {
		//connect to room
		if(!garena.initRoom()) return false;
		if(!garena.sendGCRPMeJoin()) return false;

		if(!restart || gcrp_thread.terminated) {
			gcrp_thread = new GarenaThread(garena, wc3i, GarenaThread.GCRP_LOOP);
			gcrp_thread.start();
		}
		
		// we ought to say we're starting the game; we'll do later too
		garena.startPlaying();

		return true;
	}

	public void initBot() {
		if(loadBot) {
			bot = new GChatBot(this);
			bot.init();
			
			bot.garena = garena;
			bot.plugins = plugins;
			bot.sqlthread = sqlthread;
			bot.chatthread = chatthread;

			garena.registerListener(bot);
		}
		
		if(loadSQL) {
			//initiate mysql thread
			sqlthread = new SQLThread(bot);
			sqlthread.init();
			sqlthread.start();
		}
		
		if(loadBot) {
			bot.sqlthread = sqlthread;
		}

	}
	
	public void newLogLoop() {
		if(newLogInterval != 0) {
			while(true) {
				if(System.currentTimeMillis() - lastLog > newLogInterval) {
					println("[Main] Closing old log file and creating new log file");
					log_out.close();
					String currentDate = date();
					File log_directory = new File("log/");
					if(!log_directory.exists()) {
						log_directory.mkdir();
					}
					
					File log_target = new File(log_directory, currentDate + ".log");
					
					try {
						log_out = new PrintWriter(new FileWriter(log_target, true), true);
					} catch(IOException e) {
						if(DEBUG) {
							e.printStackTrace();
						}
						println("[Main] Failed to change log file date: " + e.getLocalizedMessage());
					}
					
					if(logCommands) {
						log_cmd_out.close();
						File log_cmd_directory = new File("cmd_log/");
						if(!log_cmd_directory.exists()) {
							log_cmd_directory.mkdir();
						}
						
						File log_cmd_target = new File(log_cmd_directory, currentDate + ".log");
						
						try {
							log_cmd_out = new PrintWriter(new FileWriter(log_cmd_target, true), true);
						} catch(IOException e) {
							if(DEBUG) {
								e.printStackTrace();
							}
							println("[Main] Failed to change cmd log file date: " + e.getLocalizedMessage());
						}
					}
					lastLog = System.currentTimeMillis();
				}
				try {
					Thread.sleep(10000);
				} catch(InterruptedException e) {
					println("[Main] New day loop sleep interrupted");
				}
			}
		}
	}

	public void helloLoop() {
		int playCounter = 0;
		int reconnectCounter = 0;
		int xpCounter = 0;
		
		//see how often to reconnect
		int reconnectMinuteInterval = GCBConfig.configuration.getInt("gcb_reconnect_interval", -1);
		//divide by six to get interval measured for 10 second delays
		int reconnectInterval = -1;

		if(reconnectMinuteInterval > 0) {
			reconnectInterval = reconnectMinuteInterval * 6;
		}

		//see how often to send XP packet; every 15 minutes
		int xpInterval = 90; //15 * 60 / 10
		
		if(loadPL) {
			while(true) {
				try {
					garena.displayMemberInfo();
				} catch(IOException ioe) {
					ioe.printStackTrace();
				}

				garena.sendPeerHello();

				playCounter++;
				reconnectCounter++;
				xpCounter++;

				//handle player interval
				if(playCounter > 360000) { //1 hour
					playCounter = 0;
					garena.startPlaying(); //make sure we're actually playing
				}

				//handle reconnection interval
				if(reconnectInterval != -1 && reconnectCounter >= reconnectInterval) {
					reconnectCounter = 0;
					//reconnect to Garena room
					Main.println("[Main] Reconnecting to Garena room");
					garena.disconnectRoom();

					try {
						Thread.sleep(1000);
					} catch(InterruptedException e) {}

					initRoom(true);
				}

				//handle xp interval
				if(xpCounter >= xpInterval) {
					xpCounter = 0;

					//send GSP XP packet only if connected to room
					if(garena.room_socket.isConnected()) {
						//xp rate = 100 (doesn't matter what they actually are, server determines amount of exp gained)
						//gametype = 1001 for warcraft/dota
						garena.sendGSPXP(garena.user_id, 100, 1001);
						if(DEBUG) {
							println("[Main] Sent exp packet to Garena");
						}
					}
				}

				try {
					Thread.sleep(10000);
				} catch(InterruptedException e) {}
			}
		} else {
			//send start playing so that we don't disconnect from the room
			
			while(true) {
				
				garena.sendPeerHello();
				
				playCounter++;
				xpCounter++;
				
				//handle player interval
				if(playCounter > 360000) { //1 hour
					playCounter = 0;
					garena.startPlaying();
				}
				
				//handle xp interval
				if(xpCounter >= xpInterval) {
					xpCounter = 0;
					
					//send GSP XP packet only if connected to room
					if(garena.room_socket.isConnected()) {
						//xp rate = 100 (doesn't matter what they actually are, server determines amount of exp gained)
						//gametype = 1001 for warcraft/dota
						garena.sendGSPXP(garena.user_id, 100, 1001);
						if(DEBUG) {
							println("[Main] Sent exp packet to Garena");
						}
					}
				}

				try {
					Thread.sleep(10000);
				} catch(InterruptedException e) {}
			}
		}
	}

	/**
	 * @param args the command line arguments
	 */
	public static void main(String[] args) throws IOException {
		/* Use this to decrypt Garena packets
		try {
			GarenaEncrypt encrypt = new GarenaEncrypt();
			encrypt.initRSA();

			byte[] data = readWS(args[0]);
			byte[] plain = encrypt.rsaDecryptPrivate(data);

			byte[] key = new byte[32];
			byte[] init_vector = new byte[16];
			System.arraycopy(plain, 0, key, 0, 32);
			System.arraycopy(plain, 32, init_vector, 0, 16);
			encrypt.initAES(key, init_vector);

			data = readWS(args[1]);
			byte[] out = encrypt.aesDecrypt(data);

			Main.println(encrypt.hexEncode(out));
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.exit(0);*/

		
		Main main = new Main();
		main.init(args);

		//init log
		if(log) {
			if(newLogInterval != 0) {
				File log_directory = new File("log/");
				if(!log_directory.exists()) {
					log_directory.mkdir();
				}
				
				File log_target = new File(log_directory, date() + ".log");

				log_out = new PrintWriter(new FileWriter(log_target, true), true);
			} else {
				log_out = new PrintWriter(new FileWriter("gcb.log", true), true);
			}
		}
		if(logCommands) {
			if(newLogInterval != 0) {
				File log_cmd_directory = new File("cmd_log/");
				if(!log_cmd_directory.exists()) {
					log_cmd_directory.mkdir();
				}
				
				File log_cmd_target = new File(log_cmd_directory, date() + ".log");
				
				log_cmd_out = new PrintWriter(new FileWriter(log_cmd_target, true), true);
			} else {
				log_cmd_out = new PrintWriter(new FileWriter("gcb_cmd.log", true), true);
			}
		}
		
		DEBUG = GCBConfig.configuration.getBoolean("gcb_debug", false);

		main.initPlugins();
		if(!main.initGarena(false)) return;
		if(!main.initRoom(false)) return;
		main.initBot();
		main.loadPlugins();
		main.helloLoop();
		main.newLogLoop();
	}

	public static void println(String str) {
		Date date = new Date();
		String dateString = DateFormat.getDateTimeInstance().format(date);
		
		System.out.println(str);
		
		if(log_out != null) {
			log_out.println("[" + dateString + "] " + str);
		}
	}
	
	public static void cmdprintln(String str) {
		Date date = new Date();
		String dateString = DateFormat.getDateTimeInstance().format(date);
		
		if(log_cmd_out != null) {
			log_cmd_out.println("[" + dateString + "] " + str);
		}
	}

	public static void debug(String str) {
		if(Main.DEBUG) {
			println(str);
		}
	}
	
	public static String date() {
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
		return sdf.format(cal.getTime());
	}

	//hexadecimal string to byte array
	public static byte[] readWS(String s) {
		int len = s.length();
		
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
								 + Character.digit(s.charAt(i+1), 16));
		}
		
		return data;
	}

}