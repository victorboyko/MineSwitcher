package victor.mineswitcher;

public class Currency {
	
	private String coinAbr;
	private double usdPerDay;
	private double nethash;
	private double diff;
	
	public void setCoinAbr(String coinAbr) {
		this.coinAbr = coinAbr;
	}
	
	public String getCoinAbr() {
		return coinAbr;
	}
	
	public void setUsdPerDay(double usdPerDay) {
		this.usdPerDay = usdPerDay;
	}
	
	public double getUsdPerDay() {
		return usdPerDay;
	}
	
	public void setNethash(double nethash) {
		this.nethash = nethash;
	}
	
	public double getNethash() {
		return nethash;
	}
	
	public void setDiff(double diff) {
		this.diff = diff;
	}
	
	public double getDiff() {
		return diff;
	}

	public static String getProcessName(String launchCmd) {
		String processName = launchCmd.split(" ", 2)[0];
		processName = processName.split("\"")[processName.split("\"").length-1];
		if  (processName.contains("\\")) {
			processName = processName.substring(processName.lastIndexOf("\\")+1);	
		}
		return processName;
	}
	
	public static String getCmdArgs(String launchCmd) {
		String processName = getProcessName(launchCmd);
		String args = launchCmd.substring(launchCmd.indexOf(processName) + processName.length());
		return args;
	}
	
	public static String getCmdDir(String launchCmd) {
		String processName = getProcessName(launchCmd);
		String dir = launchCmd.substring(0, launchCmd.indexOf(processName));
		return dir;
	}
	
	
	@Override
	public String toString() {
		return String.format("%s,	prof:	$ %.2f,	diff:	%.4f,	hashrate:	%.4f", coinAbr, usdPerDay, diff, nethash);
	}
	
}
