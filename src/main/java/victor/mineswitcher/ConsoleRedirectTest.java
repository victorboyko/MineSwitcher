package victor.mineswitcher;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;

public class ConsoleRedirectTest {

	public static void main(String[] args) {

		try {
			Runtime rt = Runtime.getRuntime();
			String cmd = "C:\\mining\\hacked\\miner0.exe --server eu1-zcash.flypool.org --user doesnotmatter --pass doesnotmatter --port 3333 --cuda_devices 0 1 --templimit 88C --api";
			Process proc = new ProcessBuilder(cmd.split(" "))
//					.redirectErrorStream(true)
//					.inheritIO()
					.redirectOutput(Redirect.PIPE)
					.start();
			Thread.sleep(200);
			
			proc.getOutputStream().write("/r/n/r/n/r/n".getBytes());
			proc.getOutputStream().flush();
			
			final InputStream is = proc.getInputStream();

			Thread t = new Thread(()->{
				byte[] buffer = new byte[1024];
				int len;
				try {
					while ((len = is.read(buffer)) != -1) {
						System.out.println(len);
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
			t.start();
			
			
			Thread.sleep(2000);
			proc.destroy();
			
		} catch (Throwable t) {
			t.printStackTrace();
		}
	

	}

}
