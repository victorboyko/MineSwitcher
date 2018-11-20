package victor.mineswitcher;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.Ansi.Color;
import org.fusesource.jansi.AnsiConsole;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class Main {

	private static final Logger logger = Logger.getLogger(Main.class);
	
	private static void exit() {
		System.exit(-121);
	}
	
//	private static void killProcess(String processName) throws IOException {
//		ProcessBuilder pb = new ProcessBuilder(new String[] {"taskkill", "/F", "/IM", processName});
//		pb.redirectOutput(Redirect.INHERIT);
//	    pb.redirectError(Redirect.INHERIT);
//	    try {
//			pb.start().waitFor();
//		} catch (InterruptedException e) {
//			// do nothing
//		}
//	}
	
	private static int processCount;
	private static synchronized int getProcessNumber() {
		return ++processCount;
	}

	private static void startProcessFor1GPU(final String coinAbr, final int gpuNum) {
		logger.warn("Starting process for " + coinAbr);
		try {
			String cmd = props.getProperty(coinAbr);
			cmd = String.format(cmd, gpuNum);
			//cmd = StringEscapeUtils.escapeJava(cmd);			
			String rigName = props.getProperty("rig.name").trim();
			cmd = cmd.replace("RIGNAME", rigName);
			String cmdNoArgs = Currency.getCmdDir(cmd) + Currency.getProcessName(cmd);
			String args = Currency.getCmdArgs(cmd).trim();
			List<String> commands = new ArrayList<>();
			commands.add(cmdNoArgs);
			commands.addAll(Arrays.asList(args.split(" ")));
			logger.info("cmd: " + cmdNoArgs + ", args: " + args);
			final Process p = new ProcessBuilder(commands)
				//.directory(new File(Currency.getCmdDir(cmd)))
				//.inheritIO()
				.redirectErrorStream(true)
//				.redirectOutput(new File("out.txt"))
				.start();
//			pb.redirectOutput(Redirect.INHERIT);
//		    pb.redirectError(Redirect.INHERIT);
			synchronized (gpuProcesses) {
				gpuProcesses[gpuNum]= p;
			}
			
			final InputStream is = p.getInputStream();
			final OutputStream os = p.getOutputStream();
			
			class FilterWorkThread extends Thread {
					
				@Override
				public void run() {
					
					String line = null;
					int pNum = getProcessNumber();
					
					class PushingThread extends Thread {
						private boolean gotOutput;
						
						private synchronized boolean isGotOutput() {
							return gotOutput;
						}
						
						private synchronized void setGotOutput(boolean gotOutput) {
							this.gotOutput = gotOutput;
						}
						@Override
						public void run() {
							while(p.isAlive()) {
								try {
									Thread.sleep((long)(2.5d * 60 * 1000)); // 2.5 min
								} catch (InterruptedException e) {
									logger.error("Thread, that checks stuck processes, got interrupted: " + e);
								}
								if (!isGotOutput()) {
									logger.error(String.format("Process for GPU%s has is hung, trying to put it through..", gpuNum));
									try {
										os.flush();
									} catch (IOException e) {
										logger.error("Output for GPU%s process is not moving, restarting ...");
										p.destroy();
										startProcessFor1GPU(coinAbr, gpuNum);
										return;
									}
//									p.destroy();
//									startProcessFor1GPU(coinAbr, gpuNum);
//									return;
								}
								setGotOutput(false);
							}
						}
					}
					
					PushingThread pt = new PushingThread();
					pt.start();
					
					try {					
						BufferedReader reader = new BufferedReader(new InputStreamReader(is));
						while ((line = reader.readLine()) != null) {
							pt.setGotOutput(true);
							String cLine = pNum + "   " + line;
							boolean readyToDie = readyToDieLocks.contains(p);
							if (readyToDie) { 
								System.out.println(Ansi.ansi().fg(Color.MAGENTA).a(cLine).reset());
							} else {
								AnsiConsole.out().println(cLine);
							}
							if (line.contains("unknown error") ||
								line.contains("No CUDA device found")) {
								cmdThread.setCommand("restart");
								logger.error(String.format("Error detected: '%s'", cLine));
							}
							if (line.endsWith("+") || line.endsWith("*") || line.startsWith(">") || line.startsWith("#")
									|| line.contains("accepted") || line.contains("rejected") || line.contains(" block ")) {
								if (readyToDie) {
									readyToDieLocks.remove(p);
									p.destroy();
									System.out.println(Ansi.ansi().fg(Color.MAGENTA).a(pNum + "   stopped").reset());
								}
							}
						}
					} catch (IOException e) {
						logger.error("Unable to obtain process output: " + e);
					}
				}
			}
				
			new FilterWorkThread().start();
			new Thread(()->{
				try {
					Thread.sleep(300);
					while(p.isAlive()) {
						Thread.sleep(40);
					}
					int code = p.exitValue();
					if (code != 0 && code != 1) {
						logger.error(String.format("Process crashed for %s GPU%s with code %d, attempting to restart", coinAbr, gpuNum, code));
						Thread.sleep(1500);
						p.destroy();
						startProcessFor1GPU(coinAbr, gpuNum);
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				
			}).start();
			
				
		} catch (IOException e) {
			logger.fatal("Exiting. Failed to start process for " + coinAbr + ": " + e);
			exit();
		}
	}
		
	private static Set<Process> readyToDieLocks;
	
//	private static void stopProcessFor1GPU(String coinAbr, Process p) {
//		try {
//			readyToDieLocks.add(p);
//			synchronized (p) {
//				p.wait();				
//			}	
//			logger.info("Stopping process for " + coinAbr);
//			p.destroy();
//			//killProcess(Currency.getProcessName(props.getProperty(coinAbr)));
//		} catch (Exception e) {
//			logger.fatal("Exiting. Failed to kill process for " + coinAbr + ": " + e);
//			exit();
//		}
//	}
//	
	private static void switchProcess(final String oldCoin, final String newCoin) {
		CountDownLatch latch = new CountDownLatch(gpuCount);
		for(int i = 0; i < gpuCount; i++) {
			final int gpuNum = i;
			new Thread(()->{
				switchProcessFor1GPU(oldCoin, newCoin, gpuNum);
				latch.countDown();
			}).start();
		}
		try {
			latch.await();
		} catch (InterruptedException e) {
			logger.error("failed to wait for switching processes for all GPUs: " + e);
			exit();
		}
	}
	
	private static Process[] gpuProcesses;
	private static void switchProcessFor1GPU(final String oldCoin, final String newCoin, int gpuNum) {
		Process oldP;
		synchronized (gpuProcesses) {
			oldP = gpuProcesses[gpuNum];
		}
		if (newCoin !=null) {
			startProcessFor1GPU(newCoin, gpuNum);
		}		
		if (oldCoin != null) {
			double delayInSec = Double.valueOf(props.getProperty("time.before.killing.process").trim());
			if (newCoin != null) {
				sleep((long)(delayInSec*1000), 
						String.format("prev. coin miner will initiate shut down %.2f sec after new one is kicked off for the reasons of efficiency", delayInSec)); 
			}
			readyToDieLocks.add(oldP);
		}

	}
	
	private static String getStringByURL(URL jsonURL) throws IOException {
		HttpURLConnection conn;
		String jsonText = null;
		InputStream httpIn = null;
		
		try {
			conn = (HttpURLConnection) jsonURL.openConnection();
			
			conn.setRequestMethod("GET");
	        conn.setRequestProperty("Content-Type", 
	                   "application/x-www-form-urlencoded");
	        conn.setRequestProperty("Content-Language", "en-US"); 
	        conn.setRequestProperty("User-Agent",
	                "Mozilla/5.0 (Windows NT 5.1) AppleWebKit/535.11 (KHTML, like Gecko) Chrome/17.0.963.56 Safari/535.11");
	        conn.setUseCaches(false);
	        conn.setDoInput(true);
	        conn.setDoOutput(true);
			
			httpIn = new BufferedInputStream(conn.getInputStream());     
			jsonText = IOUtils.toString(httpIn);
		} finally {
			IOUtils.closeQuietly(httpIn);
		}
		
		return jsonText;
	}
	
	static class CommandThread extends Thread {
		private String command;
		public synchronized String getCommand() {
			String cmd = command;
			command = null;
			return cmd;
		}
		
		public synchronized void setCommand(String command) {
			this.command = command;
		}
		
		@Override
		public void run() {
			Scanner sc = new Scanner(System.in);
			try {
				while(true) {
					String cmd;
					try {
						cmd = sc.nextLine();									
					} catch (NoSuchElementException e) {
						cmd = "exit"; //ctrl+c ?
					}
					synchronized (this) {
						command = cmd;
					}
				}
			} catch (Throwable th) {
				logger.fatal("Fatal error in command input thread: " + th);
			}
		}
	}
	
	
	static Map<Double, Currency> getCurrenciesFromWJson(URL jsonURL, Set<String> coinAbrs) throws IOException, ParseException {
		HttpURLConnection conn;
		String jsonText = null;
		InputStream httpIn = null;
		jsonText = getStringByURL(jsonURL);
		JSONParser parser = new JSONParser();
		
		Map<Double, Currency> currencies = new TreeMap<>(Comparator.reverseOrder());
		
		Object obj;
		obj = parser.parse(jsonText);
		JSONObject topJson = (JSONObject)obj;
		Collection<Object> coins = topJson.entrySet();
		logger.info(String.format("Found info about %d currencies", coins.size()));
		for(Object coinWrap : coins) {
		
			Entry<Object, Object> coinPairVals = (Entry<Object, Object>)coinWrap;
			
			
			
			String coinAbr = (String)coinPairVals.getKey();
			if (!coinAbrs.contains(coinAbr)) {
				continue;
			}
			
			JSONArray coinJson = (JSONArray)coinPairVals.getValue();
			
			Currency curr = new Currency();
			curr.setCoinAbr(coinAbr);
			
			Object profitability = coinJson.get(0);
			curr.setUsdPerDay(Double.valueOf(profitability.toString()));
			
			if (coinJson.size() > 1) {
				Object diff = coinJson.get(1);
				curr.setDiff(Double.valueOf(diff.toString()));
			}
			
			if (coinJson.size() > 2) {
				Object nethash = coinJson.get(2);
				curr.setNethash(Double.valueOf(nethash.toString()));
			}
			
			double d;
			for(d = 0; currencies.containsKey(curr.getUsdPerDay()+d); d+=0.000001d){
			}

			currencies.put(curr.getUsdPerDay()+d, curr);
		}
		
		return currencies;
	}

	private static Currency currentCoin = null;
	private static Properties props = new Properties();
	private static double globalProf;
	private static long globalStart, startTime;
	private static CommandThread cmdThread;
	private static int gpuCount;

	private static double neededIncrease(long timePassed) {
		return 1 + 20 / (timePassed / 1000d + 3);
	}

	public static void main(String[] args) {
		AnsiConsole.systemInstall();

		logger.info("MineSwitcher v0.3!\nType 'help' for command list");
		
		
		try {
			String propStr = FileUtils.readFileToString(new File("switcher.properties"));
			props.load(new StringReader(propStr.replace("\\","\\\\")));
		} catch (FileNotFoundException e) {
			logger.error("no switcher.properties found");
			exit();
		} catch (IOException e) {
			logger.error("error reading properties: " + e);
			exit();
		}
		
		cmdThread = new CommandThread();
		cmdThread.start();
		
		String jsonURLstr = props.getProperty("minehub.json.url");
		URL jsonURL = null;
		try { 
			jsonURL = new URL(jsonURLstr);
		} catch (MalformedURLException e) {
			logger.error("URL is incorrect : " + e);
			exit();
		}
		
		
		Map<Double, Currency> currencies = null;
		
		int delay_between_calls = Integer.valueOf(props.getProperty("minehub.update.frequency").trim()) * 1000; // sec
		int delay_between_commands = 50; // milliseconds
		gpuCount = Integer.valueOf(props.getProperty("gpu.count").trim());
		gpuProcesses = new Process[gpuCount];
		readyToDieLocks = Collections.synchronizedSet(new HashSet<>());
		
		Set<String> coinNames = new HashSet<>(Arrays.asList(props.getProperty("active.coins").split(",")));
		
		long localStart= startTime = globalStart = System.currentTimeMillis();
		
		new Thread(()->{
			while(true) {					
				String cmd = cmdThread.getCommand();
				if (cmd != null && cmd.length() > 0) {
					processCommand(cmd);
				}
				sleep(delay_between_commands, null);
			}
		}).start();
		
		try {
			
			while(true) {
			
				try {
					currencies = getCurrenciesFromWJson(jsonURL, coinNames);
				} catch (IOException e) {
					logger.error(String.format("Error getting json by url%s: %s", e, currencies == null ? "" : " (old data will be used)"));
				} catch (ParseException e) {
					logger.error(String.format("Bad json%s: %s", e, currencies == null ? "" : " (old data will be used)"));
				}
				
				if (currencies == null) {
					logger.info("Can't get info from whattomine, so mining ZEC for now");
					Currency c = new Currency();
					c.setCoinAbr("ZEC");
					switchProcess(currentCoin == null ? null : currentCoin.getCoinAbr(), (currentCoin = c).getCoinAbr());
					
				} else {
				
					if (currencies.size() == 0) {
						logger.info("no matching currencies, so mining ZEC for now");
						Currency c = new Currency();
						c.setCoinAbr("ZEC");
						switchProcess(currentCoin == null ? null : currentCoin.getCoinAbr(), (currentCoin = c).getCoinAbr());
					} else {
						for(Currency c : currencies.values()) {
							logger.debug(c);
							if (currentCoin!= null && c.getCoinAbr().equals(currentCoin.getCoinAbr())) {
								currentCoin = c;
							}
						}
						
						Currency c = currencies.values().iterator().next();
						long delta = System.currentTimeMillis() - startTime;
						long localDelta = System.currentTimeMillis() - localStart;
						if (currentCoin != null) {
							globalProf = ((localStart-globalStart)*globalProf + localDelta*currentCoin.getUsdPerDay())/(localStart-globalStart+localDelta);
						} 
						if (currentCoin == null || shouldSwitch(currentCoin.getUsdPerDay(), c.getUsdPerDay(), delta)) {						
							switchProcess(currentCoin == null ? null : currentCoin.getCoinAbr(), (currentCoin = c).getCoinAbr());
							startTime = System.currentTimeMillis();
						}
						
						localStart = System.currentTimeMillis();
	
					}
				}
				
				logger.warn("Currently mining: " + currentCoin);
				Thread.sleep(delay_between_calls);
	
			}
		} catch (Throwable th) {
			logger.fatal("Fatal error: " + th);
		}
       

	}
	

	
	private static boolean shouldSwitch(double currentProf, double newProf, long timePassed /* millis */) {
		boolean result = newProf / currentProf > neededIncrease(timePassed);
		return result;
	}
	
	static void processCommand(String cmd) {
		cmd = cmd.trim();
		if (cmd.equalsIgnoreCase("exit")) {
			logger.info("Exiting");
			for(Process p : gpuProcesses) {
				if (p != null) {
					p.destroy();
				}
			}
			//switchProcess(currentCoin.getCoinAbr(), null); // I will no synch the argumens just for the 'exit' command
			sleep(1000, "giving a chance for stopping process to complete");
			exit();
		}
		if (cmd.equalsIgnoreCase("help")) {
			StringBuilder sb = new StringBuilder();
			sb.append("Supported commands:").append("\n")
				.append("help	- for this message").append("\n")
				.append("exit	- to stop mining and exit").append("\n")
				.append("stat	- statistics").append("\n");
			logger.info(sb);
			return;
		}
		if (cmd.equalsIgnoreCase("stat")) {
			logger.info(String.format("Stats:\n Average profitability -	$ %.2f\n run time - 	%.2fh\n sufficient increase -	%.2f%%", 
					globalProf, (System.currentTimeMillis() - globalStart)/(3600d*1000), (neededIncrease(System.currentTimeMillis()-startTime)-1)*100));
			return;
		}
		if (cmd.equalsIgnoreCase("restart")) {
			logger.info("restarting process for " + currentCoin.getCoinAbr());
			switchProcess(currentCoin.getCoinAbr(), currentCoin.getCoinAbr());
			return;
		}
			
		
		logger.info(String.format("Command unknown: '%s'", cmd));
	}
	
	private static void sleep(long millis, String message) {
		try {
			if (millis >= 500) {
				logger.debug(String.format("Sleeping for '%s' %.2f sec", message == null ? "" : message, millis / 1000d));
			}
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			logger.error("Sleeping interrupted, some impactful consequnces can be expected: " + e);
		}
	}

}
