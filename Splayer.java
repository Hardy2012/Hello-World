import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.Format;
import java.util.HashMap;
import java.util.concurrent.Semaphore;

import javax.media.Manager;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.omg.CORBA.PRIVATE_MEMBER;

import sun.misc.Lock;
import sun.print.resources.serviceui;

import com.sun.corba.se.impl.orbutil.concurrent.Mutex;
import com.sun.corba.se.spi.ior.Writeable;
import com.sun.media.parser.audio.WavParser;

class Fifo {
	private byte[] buff;
	private int readIndex, writeIndex;
	private int length;
	private int used;
	private int lock;
	
	private void fifoLock() {
		while (lock == 1) {
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		lock = 1;
	}
	
	private void fifoUnlock() {
		lock = 0;
	}

	public Fifo(int size) {
		length = size;
		buff = new byte[length];
		readIndex = 0;
		writeIndex = 0;
		used = 0;
		lock = 0;
	}

	public int write(byte[] inBuff, int size) {
		fifoLock();
		
		if (used + size > length) {
			fifoUnlock();
			return -1;
		}

		byte tmp = buff[0];
		for (int i = 0; i < size; i++) {
			buff[writeIndex] = inBuff[i];
			writeIndex++;
			writeIndex %= length;
		}
		used += size;
		
		fifoUnlock();
		
		return size;
	}

	public int read(byte[] outBuff, int size) {
		fifoLock();
		
		if (used < size) {
			fifoUnlock();
			return -1;
		}

		for (int i = 0; i < size; i++) {
			outBuff[i] = buff[readIndex];
			readIndex++;
			readIndex %= length;
			used--;
		}
		
		fifoUnlock();

		return size;
	}

	public boolean isFull() {
		return used == length;
	}

	public boolean isEmpty() {
		return used == 0;
	}
	
	public int getLength() {
		return length;
	}
}

class HttpFile {
	private Fifo fifo;
	private URL url;
	private DataInputStream in;
	private PrintWriter out;
	private Socket skt;
	private HashMap<String, String> head;
	
	class DownloadThread extends Thread {
		private Fifo fifo;
		private int size;
		public DownloadThread (Fifo downFifo, int downSize) {
			size = downSize;
			fifo = downFifo;
		}
		@Override
		public void run() {
			// TODO Auto-generated method stub
			super.run();
			byte[] buff = new byte[1024];
			int readLen;
			int count = 0;
			while (count < size) {
				try {
					readLen = in.read(buff, 0, buff.length);
					if (readLen <= 0) {
						System.err.println("read error");
						break;
					}
					
					while (fifo.write(buff, readLen) == -1) {
						try {
							Thread.sleep(50);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					count += readLen;
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
			}		
		}
	}

	public HttpFile(String path) {
		try {
			url = new URL(path);
			skt = new Socket(url.getHost(), 80);
			in = new DataInputStream(skt.getInputStream());
			out = new PrintWriter(skt.getOutputStream(), true);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		head = new HashMap<String, String>();
		out.print("GET " + url.getPath() + " HTTP/1.0 \r\n\r\n");
		out.flush();
		getHead();

		int contenSize;
		contenSize = Integer.valueOf(getHeadInfo("Content-Length"));

		fifo = new Fifo(contenSize / 10);
		DownloadThread download = new DownloadThread(fifo, contenSize);
		download.start();
	}

	private void parseHeadString(String headString) {
		String[] hash;

		if (headString.matches(".*:.*")) {
			hash = headString.split(": +");
			head.put(hash[0], hash[1]);
		}
	}

	private void getHead() {
		try {
			String headString;
			while ((headString = in.readLine()) != null) {
				if (headString.length() == 0) {
					break;
				}

				parseHeadString(headString);
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public String getHeadInfo(String headKey) {
		System.out.println(headKey);
		System.out.println(head.get(headKey));
		return head.get(headKey);
	}

	public Fifo getFifo() {
		return fifo;
	}
}

class WavPlayer {
	private AudioFormat format;
	private int bufferSize;
	private SourceDataLine line;
	private byte[] lineBuff;
	private Fifo fifo;

	public WavPlayer(Fifo wavFifo) {
		byte[] buff = new byte[512];
		fifo = wavFifo;
		
		while (-1 == fifo.read(buff, buff.length)) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		ByteArrayInputStream in = new ByteArrayInputStream(buff);
		
		try {
			format = AudioSystem.getAudioFileFormat(in).getFormat();
			System.out.println(format.getChannels());
			System.out.println(format.getFrameRate());
			System.exit(0);
		} catch (UnsupportedAudioFileException e1) {
			// TODO Auto-generated catch block
			System.out.println("aff");
			e1.printStackTrace();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	
		bufferSize = format.getFrameSize()
				* Math.round(((AudioFormat) format).getSampleRate() / 10);
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
		
		try {
			line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(format, bufferSize);
		} catch (LineUnavailableException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void play() {
		line.start();

		// read from net work and play back
		lineBuff = new byte[bufferSize];
		int readLen;
		while (true) {
			readLen = fifo.read(lineBuff, bufferSize);
			if (readLen != bufferSize) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				continue;
			}

			line.write(lineBuff, 0, bufferSize);
		}
	}

	public void close() {
		line.drain();
		line.close();
	}
}

public class Splayer {
	HttpFile audioFile;
	Fifo audioFifo;
	WavPlayer wPlayer;

	public Splayer(String urlString) {
		audioFile = new HttpFile(urlString);
		audioFifo = audioFile.getFifo();
		wPlayer = new WavPlayer(audioFifo);
	}

	public void play() {
		wPlayer.play();
	}

	public static void main(String[] args) {
		Splayer player = new Splayer("file:///wind.mp3");
//		Splayer player = new Splayer("http://192.168.1.130/huanyin.wav");
		player.play();
	}
}