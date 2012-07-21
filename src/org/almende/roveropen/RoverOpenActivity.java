package org.almende.roveropen;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.MotionEvent;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Socket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;


public class RoverOpenActivity extends Activity implements
AccelerometerListener {
	// Image directory for storing snapshots
	private static final String IMAGE_DIRECTORY = "RoverOpen";
	private static final String TAG = "MAIN_ACTIVITY";

	// The different menu options
	private static final int START_ID = Menu.FIRST;
	private static final int SNAPSHOT_ID = Menu.FIRST + 1;
	private static final int STOP_ID = Menu.FIRST + 2;
	private static final int CONTROLS_ID = Menu.FIRST + 3;
	private static final int ABOUT_ID = Menu.FIRST + 4;
	private static final int EXIT_ID = Menu.FIRST + 5;

	// Leaving references to OpenCV intact so we can e.g. add face tracking
	// easily
	private String mCurrentImagePath = null;
	private OpenCV opencv = new OpenCV();

	private ImageView mImageView;

	private static Context CONTEXT;

	// TCP/IP sockets
	private Socket cSock;
	private Socket vSock;
	private Handler mMainHandler;
	private Bitmap bitmap;

	// Flags to store state of the connection
	boolean streaming = false;
	boolean snapshot = true;
	boolean autoconnect = false;
	boolean controls = false;

	// Infrared on/off
	boolean infrared;

	// Sensitivity towards acceleration
	int sensitivity = 24;

	// We put commands in a buffer which is treated as a list, newer commands
	// overwrite old ones if they are not send quick enough to the robot, but
	// that should be no problem (and is on purpose)
	int maxCmdQueue = 4;
	byte[] cmdQueue = new byte[maxCmdQueue];
	byte cmdReadIndex = 0;
	byte cmdWriteIndex = 0;

	// The maximal image buffer will be sufficient for hi-res images, notice
	// that jpeg images do not have a default image size. The tcp buffer is
	// large enough for the packages send by the Rover, but the latter tends
	// to chop images across multiple TCP chunks so it is not large enough
	// for one image.
	int maxTCPBuffer = 2048;
	int maxImageBuffer = 131072;
	byte[] imageBuffer = new byte[maxImageBuffer];
	int imagePtr = 0;
	int tcpPtr = 0;

	// Should only be true when debugging this app
	private boolean bDebug;

	// Buttons
	ImageButton btnIR;
	ImageButton btnUpLeft;
	ImageButton btnUpRight;
	ImageButton btnDownRight;
	ImageButton btnDownLeft;

	private boolean rightup;
	private boolean leftup;
	private boolean rightdown;
	private boolean leftdown;

	private boolean stopClock = false;
	private boolean clockTick = false;

	/**************************************************************************
	 * Setters/getters
	 *************************************************************************/
	// Used by the accelerometer to get a handle on this class
	public static Context getContext() {
		return CONTEXT;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		bDebug = false;
		mImageView = (ImageView) findViewById(R.id.ImageViewMain); // new ImageView(this);
		setContentView(R.layout.main);
		CONTEXT = this;
		for (int i = 0; i < maxCmdQueue; i++)
			cmdQueue[i] = 0;

		getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);

		new Thread(new ClockRunnable()).start();

		WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		String welcomeText = "Welcome to RoverOpen!\n\n";
		TextView text = (TextView) findViewById(R.id.TextViewMain);
		if (wifi != null) {
			WifiInfo info = wifi.getConnectionInfo();
			if (info != null) {
				String ssid = info.getSSID();
				if (ssid != null) {
					if (ssid.contains("AC13")) {
						welcomeText += "You are connected to the robot, good!\n";
						autoconnect = true;
					} else {
						welcomeText += "You first need to be connected to the robot!\n"
								+ "It should be a connection starting with AC13. "
								+ "If you do not see a Wifi connection with that name, make sure your cell phone can connect to an ad-hoc network!\n";
					}
				} else {
					welcomeText += "it is not possible to determine the name of your Wifi connection "
							+ "make sure you are connected!\n";
				}
			}
		}
		if (text != null) {
			text.setText(welcomeText, TextView.BufferType.NORMAL);
		} else {
			Log.d("TextView", "is empty");
		}

		// Set button background to transparent
		btnIR = (ImageButton) findViewById(R.id.btnIR);
		btnUpLeft = (ImageButton) findViewById(R.id.btnUpLeft);
		btnUpRight = (ImageButton) findViewById(R.id.btnUpRight);
		btnDownRight = (ImageButton) findViewById(R.id.btnDownRight);
		btnDownLeft = (ImageButton) findViewById(R.id.btnDownLeft);
		if (btnIR == null) {
			Log.d(TAG, "Cannot find button");
		}
		btnIR.setBackgroundColor(android.R.color.transparent);
		btnUpLeft.setBackgroundColor(android.R.color.transparent);
		btnUpRight.setBackgroundColor(android.R.color.transparent);
		btnDownRight.setBackgroundColor(android.R.color.transparent);
		btnDownLeft.setBackgroundColor(android.R.color.transparent);
		SetButtonVisible(false);

		btnIR.setOnTouchListener(new OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent e) {
				int action = e.getAction();
				switch (action & MotionEvent.ACTION_MASK) {
				case MotionEvent.ACTION_DOWN:
					Log.i(TAG, "IR toggle");
					if (infrared)
						cmdQueue[cmdWriteIndex] = 10;
					else 
						cmdQueue[cmdWriteIndex] = 11;
					infrared = !infrared;
					cmdWriteIndex++;
					cmdWriteIndex %= maxCmdQueue;
					break;
				}
				return true;
			}
		});

		btnUpRight.setOnTouchListener(new OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent e) {
				int action = e.getAction();
				switch (action & MotionEvent.ACTION_MASK) {
				case MotionEvent.ACTION_CANCEL:
				case MotionEvent.ACTION_UP:
					rightup = false;
					leftup = false;
					//					Stop();
					break;
				case MotionEvent.ACTION_POINTER_UP:
					leftup = false;
					break;
				case MotionEvent.ACTION_DOWN:
					rightup = true;
					break;
				case MotionEvent.ACTION_POINTER_DOWN:
					int ptrId = action >> MotionEvent.ACTION_POINTER_INDEX_SHIFT; int ptrIdx = e.findPointerIndex(ptrId); 
					Rect rect = new Rect();
					btnUpLeft.getGlobalVisibleRect(rect);
					int location[] = { 0, 0 };
					v.getLocationOnScreen(location);
					int rawX = (int) e.getX(ptrIdx) + location[0];
					int rawY = (int) e.getY(ptrIdx) + location[1];
					if (rect.contains(rawX, rawY)) {
						leftup = true;
					}
					break;
		case MotionEvent.ACTION_MOVE:
			if (ReadyForCommand()) {
				//						Drive();
				//						sleep(300);
				// maybe get from http://stackoverflow.com/questions/5944154/android-peculiar-touchevent-scenario-to-handle
				if (clockTick == true) {
					clockTick = false;
					Drive();
				}
			}
			break;
				}
				return true;
			}
		});

		btnUpLeft.setOnTouchListener(new OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent e) {
				int action = e.getAction();
				switch (action & MotionEvent.ACTION_MASK) {
				case MotionEvent.ACTION_CANCEL:
				case MotionEvent.ACTION_UP:
					leftup = false;
					rightup = false;
					//					Stop();
					break;
				case MotionEvent.ACTION_POINTER_UP:
					rightup = false;
					break;
				case MotionEvent.ACTION_DOWN:
					leftup = true;
					break;
				case MotionEvent.ACTION_POINTER_DOWN:
					int ptrId = action >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
					int ptrIdx = e.findPointerIndex(ptrId); 
					Rect rect = new Rect();
					btnUpRight.getGlobalVisibleRect(rect);
					int location[] = { 0, 0 };
					v.getLocationOnScreen(location);
					int rawX = (int) e.getX(ptrIdx) + location[0];
					int rawY = (int) e.getY(ptrIdx) + location[1];
					Log.i(TAG, "Something else pressed: " + rawX + "," + rawY);
					if (rect.contains(rawX, rawY)) {
						rightup = true;
					}
					break;					
				case MotionEvent.ACTION_MOVE:
					if (ReadyForCommand()) {
						if (clockTick == true) {
							clockTick = false;
							Drive();
						}
					}
					break;
				}
				return true;
			}
		});

		btnDownRight.setOnTouchListener(new OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent e) {
				int action = e.getAction();
				switch (action & MotionEvent.ACTION_MASK) {
				case MotionEvent.ACTION_CANCEL:
				case MotionEvent.ACTION_UP:
					rightdown = false;
					leftdown = false;
					//					Stop();
					break;
				case MotionEvent.ACTION_DOWN:
					rightdown = true;
					break;
				case MotionEvent.ACTION_POINTER_UP:
					leftdown = false;
					break;
				case MotionEvent.ACTION_POINTER_DOWN:
					int ptrId = action >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
					int ptrIdx = e.findPointerIndex(ptrId); 
					Rect rect = new Rect();
					btnDownLeft.getGlobalVisibleRect(rect);
					int location[] = { 0, 0 };
					v.getLocationOnScreen(location);
					int rawX = (int) e.getX(ptrIdx) + location[0];
					int rawY = (int) e.getY(ptrIdx) + location[1];
					if (rect.contains(rawX, rawY)) {
						leftdown = true;
					}
					break;
				case MotionEvent.ACTION_MOVE:
					if (ReadyForCommand()) {
						if (clockTick == true) {
							clockTick = false;
							Drive();
						}
					}
					break;
				}
				return true;
			}
		});

		btnDownLeft.setOnTouchListener(new OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent e) {
				int action = e.getAction();
				switch (action & MotionEvent.ACTION_MASK) {
				case MotionEvent.ACTION_CANCEL:
				case MotionEvent.ACTION_UP:
					leftdown = false;
					rightdown = false;
					//					Stop();
					break;
				case MotionEvent.ACTION_POINTER_UP:
					rightdown = false;
					break;
				case MotionEvent.ACTION_DOWN:
					leftdown = true;
					break;
				case MotionEvent.ACTION_POINTER_DOWN:
					int ptrId = action >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
					int ptrIdx = e.findPointerIndex(ptrId); 
					Rect rect = new Rect();
					btnDownRight.getGlobalVisibleRect(rect);
					int location[] = { 0, 0 };
					v.getLocationOnScreen(location);
					int rawX = (int) e.getX(ptrIdx) + location[0];
					int rawY = (int) e.getY(ptrIdx) + location[1];
					//Log.d(TAG, "Something else pressed: " + rawX + "," + rawY);
					if (rect.contains(rawX, rawY)) {
						rightdown = true;
					}
					break;
				case MotionEvent.ACTION_MOVE:
					if (ReadyForCommand()) {
						// only executed every 500 ms
						if (clockTick == true) {
							clockTick = false;
							Drive();
						}
					}
					break;
				}
				return true;
			}
		});

		/*
		 * Create the main handler on the main thread so it is bound to the main
		 * thread's message queue.
		 */
		mMainHandler = new Handler() {
			public void handleMessage(Message msg) { // msg.what = 0
				if (bDebug)
					Log.d(TAG, "Update view with new image");
				mImageView = (ImageView) findViewById(R.id.ImageViewMain);

				if (mImageView != null) {
					mImageView.setImageBitmap(bitmap);
				} else {
					Log.e(TAG, "Image view is null!");
				}
			}
		};

		// If Wifi connection with right SSID found, start streaming automatically
		if (autoconnect) {
			startStreaming();
		}
	}

	class ClockRunnable implements Runnable {
		public void run() {
			while(true) {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				if(stopClock) break;
				clockTick = true;
			}
		}
	}


	public boolean checkConnection() {
		WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		if (wifi != null) {
			WifiInfo info = wifi.getConnectionInfo();
			if (info != null) {
				String ssid = info.getSSID();
				if (ssid != null) 
					if (ssid.contains("AC13")) 
						return true;
			}
		}
		return false;
	}

	public boolean ReadyForCommand() {
		return true;
		//		return (cmdQueue[cmdWriteIndex] == 0);
	}

	public void Stop() {
		cmdQueue[cmdWriteIndex] = 0;
		cmdWriteIndex++;
		cmdWriteIndex %= maxCmdQueue;
	}

	public void Drive() {
		if (rightup && !leftup)
			Drive(2, 0); 
		else if (!rightup && leftup)
			Drive(1, 0);
		else if (leftup && rightup) 
			Drive(3, 0);

		if (rightdown && !leftdown)
			Drive(0, 2); 
		else if (!rightdown && leftdown)
			Drive(0, 1);
		else if (leftdown && rightdown) 
			Drive(0, 3);

	}

	public void SetButtonVisible(boolean visible) {
		int visEnum = 0;
		if (visible) {
			visEnum = View.VISIBLE;
		} else {
			visEnum = View.INVISIBLE;
		}
		if (btnIR != null) 
			btnIR.setVisibility(visEnum); 
		else {
			Log.d(TAG, "SetButtonVisible fails because button is null");
			//			ImageButton btnIR = new ImageButton(this);
			//			btnIR.setId(btnIR);
			//			btnIR.setBackgroundResource(R.drawable.square_ir);
			//			addView(btnIR);
		};
		if (btnUpLeft != null) btnUpLeft.setVisibility(visEnum);
		if (btnUpRight != null) btnUpRight.setVisibility(visEnum);
		if (btnDownRight != null) btnDownRight.setVisibility(visEnum);
		if (btnDownLeft != null) btnDownLeft.setVisibility(visEnum);
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (AccelerometerManager.isSupported()) {
			AccelerometerManager.startListening(this);
		}
	}

	@Override
	protected void onDestroy() {
		streaming = false;
		super.onDestroy();
		if (AccelerometerManager.isListening()) {
			AccelerometerManager.stopListening();
		}
		killApp(true);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		menu.add(0, START_ID, 1, "Start");
		menu.add(0, SNAPSHOT_ID, 2, "Snapshot");
		menu.add(0, STOP_ID, 3, "Stop");
		menu.findItem(SNAPSHOT_ID).setVisible(false);
		menu.add(0, CONTROLS_ID, 4, "Controls");
		menu.findItem(CONTROLS_ID).setVisible(false);
		menu.add(0, ABOUT_ID, 5, getResources().getString(R.string.about))
		.setIcon(R.drawable.ic_menu_about);
		menu.add(0, EXIT_ID, 6, "Exit");
		return true;
	}

	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		switch (item.getItemId()) {
		case START_ID:
			startStreaming();
			return true;
		case SNAPSHOT_ID:
			snapshot = true;
			return true;
		case STOP_ID:
			streaming = false;
			return true;
		case CONTROLS_ID:
			controls = !controls;
			SetButtonVisible(controls);
			return true;
		case ABOUT_ID:
			About about = new About();
			about.show(this);
			return true;
		case EXIT_ID:
			finish();
			return true;
		}

		return super.onMenuItemSelected(featureId, item);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		menu.findItem(SNAPSHOT_ID).setVisible(streaming);
		menu.findItem(START_ID).setVisible(!streaming);
		menu.findItem(CONTROLS_ID).setVisible(streaming);
		menu.findItem(STOP_ID).setVisible(streaming);
		return super.onPrepareOptionsMenu(menu);
	}

	public static void killApp(boolean killSafely) {
		if (killSafely) {
			System.runFinalizersOnExit(true);
			System.exit(0);
		} else {
			android.os.Process.killProcess(android.os.Process.myPid());
		}
	}

	public void startStreaming() {
		if (!streaming) {
			if (checkConnection()) {
				streaming = true;
				Thread cThread = new Thread(new ClientThread());
				cThread.start();
				TextView text = (TextView) findViewById(R.id.TextViewMain);
				text.setVisibility(View.INVISIBLE);
			}
			else {
				int sec = 6;
				Toast toast = Toast.makeText(CONTEXT, "Sorry! You don't seem to have a connection set up to the AC13 Rover. It starts with \"AC13\" and you need ad-hoc capabilities to connect to it", sec);
				toast.show();
			}
		}
	}

	/**
	 * Drive backwards or forwards. By setting one of these to "0" there will be
	 * no command in that direction. By setting it to "1" it will go right. By
	 * setting it to "2" it will drive left. By setting it to "3" both sides
	 * will be driven. It is possible to set both forward and backwards to "3",
	 * or other weird combinations. The result will just be a quick succession of
	 * forward and backward commands to the robot.
	 * 
	 * @param forward
	 * @param backward
	 */
	public void Drive(int forward, int backward) {
		switch (forward) {
		case 1:
			Log.i("Move cmd", "right forward");
			cmdQueue[cmdWriteIndex] = 5;
			cmdWriteIndex++;
			cmdWriteIndex %= maxCmdQueue;
			break;
		case 2:
			Log.i("Move cmd", "left forward");
			cmdQueue[cmdWriteIndex] = 7;
			cmdWriteIndex++;
			cmdWriteIndex %= maxCmdQueue;
			break;
		case 3:
			Log.i("Move cmd", "forward");
			cmdQueue[cmdWriteIndex] = 5;
			cmdWriteIndex++;
			cmdWriteIndex %= maxCmdQueue;
			cmdQueue[cmdWriteIndex] = 7;
			cmdWriteIndex++;
			cmdWriteIndex %= maxCmdQueue;
			break;
		}

		switch (backward) {
		case 1:
			Log.i("Move cmd", "right backward");
			cmdQueue[cmdWriteIndex] = 6;
			cmdWriteIndex++;
			cmdWriteIndex %= maxCmdQueue;
			break;
		case 2:
			Log.i("Move cmd", "left backward");
			cmdQueue[cmdWriteIndex] = 8;
			cmdWriteIndex++;
			cmdWriteIndex %= maxCmdQueue;
			break;
		case 3:
			Log.i("Move cmd", "backward");
			cmdQueue[cmdWriteIndex] = 6;
			cmdWriteIndex++;
			cmdWriteIndex %= maxCmdQueue;
			cmdQueue[cmdWriteIndex] = 8;
			cmdWriteIndex++;
			cmdWriteIndex %= maxCmdQueue;
			break;
		}
	}

	public void onAccelerationChanged(float x, float y, float z, boolean tx) {
		if (tx && streaming && !controls) {
			// convert to [-127,127]
			int speed = (int) (x * (127.0F / 9.9F));

			// convert to [-127,127]
			int offset = (int) (y * (127.0F / 9.9F));

			Log.i("Speeds", "speed=" + speed + ", offset=" + offset); 

			if (speed < -sensitivity) {
				if (offset > sensitivity) {
					Drive(1, 0);
				} else if (offset < -sensitivity) {
					Drive(2, 0);
				} else {
					Drive(3, 0);
				}
			}

			if (speed > sensitivity) {
				if (offset > sensitivity) {
					Drive(0, 1);
				} else if (offset < -sensitivity) {
					Drive(0, 2);
				} else {
					Drive(0, 3);
				}
			}
		}
	}

	/**
	 * Separate thread to handle TCP/IP data stream. The result is stored in
	 * image1 and image2. It is communicated to the original activity via the
	 * handler.
	 */
	public class ClientThread implements Runnable {
		public void run() {
			try {
				cSock = new Socket("192.168.1.100", 80);
				cSock.setSoTimeout(0);
				WriteStart();
				ReceiveAnswer(0);
				cSock.close();
				cSock = new Socket("192.168.1.100", 80);
				cSock.setSoTimeout(0);
				byte[] buffer = new byte[2048];
				for (int i = 1; i < 4; i++) {
					WriteCmd(i, null);
					buffer = ReceiveAnswer(i);
				}
				byte[] imgid = new byte[4];
				for (int i = 0; i < 4; i++)
					imgid[i] = buffer[i + 25];

				vSock = new Socket("192.168.1.100", 80);
				WriteCmd(4, imgid);
				while (streaming) {
					if (cmdQueue[cmdReadIndex] > 0) {
						WriteCmd(cmdQueue[cmdReadIndex], null);
						cmdQueue[cmdReadIndex] = 0;
						cmdReadIndex++;
						cmdReadIndex %= maxCmdQueue;
					}
					ReceiveImage();
				}
				cSock.close();
				vSock.close();

			} catch (Exception e) {
				Log.e(TAG, "Socket read error", e);
			}
		}

		public void WriteStart() {
			try {
				if (bDebug)
					Log.d("WriteStart", "HTTP GET cmd (authentication)");
				PrintWriter out = new PrintWriter(new BufferedWriter(
						new OutputStreamWriter(cSock.getOutputStream())), true);
				out.println("GET /check_user.cgi?user=AC13&pwd=AC13 HTTP/1.1\r\nHost: 192.168.1.100:80\r\nUser-Agent: WifiCar/1.0 CFNetwork/485.12.7 Darwin/10.4.0\r\nAccept: */*\r\nAccept-Language: en-us\r\nAccept-Encoding: gzip, deflate\r\nConnection: keep-alive\r\n\r\n!");
			} catch (Exception e) {
				Log.e("ClientActivity", "S: Error", e);
			}
		}

		public byte[] ReceiveAnswer(int i) {
			byte[] buffer = new byte[2048];
			try {
				int len = cSock.getInputStream().read(buffer, 0, 2048);
				if (len > 0) {
					String str = new String(buffer, 0, len);
					if (bDebug)
						Log.d("Read i=" + i, str);
				}
			} catch (Exception eg) {
				Log.e("ReceiveAnswer", "General: input stream error", eg);
				eg.printStackTrace();
			}
			return buffer;
		}

		public boolean ImgStart(byte[] start) {
			return (start[0] == 'M' && start[1] == 'O' && start[2] == '_' && start[3] == 'V');
		}

		public void ReceiveImage() {
			if (bDebug)
				Log.d("ReceiveImage", "Get image");
			try {
				int len = 0;
				int newPtr = tcpPtr;
				int imageLength = 0;
				boolean fnew = false;
				while (!fnew && newPtr < maxImageBuffer - maxTCPBuffer) {
					len = vSock.getInputStream().read(imageBuffer, newPtr,
							maxTCPBuffer);
					// todo: check if this happens too often and exit
					if (len <= 0) continue;

					byte[] f4 = new byte[4];
					for (int i = 0; i < 4; i++)
						f4[i] = imageBuffer[newPtr + i];
					if (ImgStart(f4) && (imageLength > 0))
						fnew = true;
					if (!fnew) {
						newPtr += len;
						imageLength = newPtr - imagePtr;
					} else {
						if (bDebug)
							Log.i("ReceiveImage", "Total image size is "
									+ (imageLength - 36));

						Bitmap rawmap = BitmapFactory.decodeByteArray(
								imageBuffer, imagePtr + 36, imageLength - 36);
						if (rawmap != null) {
							//							Matrix matrix = new Matrix();
							//							matrix.postRotate(90);
							mImageView = (ImageView) findViewById(R.id.ImageViewMain); 
							if (mImageView != null) {
								//								bitmap = Bitmap.createBitmap(rawmap, 0, 0,
								//										mImageView.getWidth(), mImageView.getHeight(),
								//										matrix, true);
								// preserve aspect ratio by using height as indicative
								int newwidth = mImageView.getHeight() * rawmap.getWidth() / rawmap.getHeight();
								bitmap = Bitmap.createScaledBitmap(rawmap,
										newwidth, mImageView.getHeight(),
										true);
							} else  {
								Log.e(TAG, "Cannot convert");
								bitmap = rawmap;
							}
							mMainHandler.sendEmptyMessage(0);
							if (snapshot) {
								SimpleDateFormat formatter = (SimpleDateFormat) DateFormat
										.getDateTimeInstance();
								formatter.applyPattern("yyyy-MM-dd-hh.mm.ss");
								String currentDateTimeString = formatter
										.format(new Date());
								File f = new File(
										Environment
										.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
										IMAGE_DIRECTORY);

								if (!f.exists()) {
									if (bDebug)
										Log.d("Create directory", f.mkdirs()
												+ "");
								} else {
									if (bDebug)
										Log.d("Directory already exists?",
												f.getAbsolutePath());
								}
								String strFilePath = f.getAbsolutePath()
										+ "/AC13_" + currentDateTimeString
										+ ".jpeg";
								Log.d("Store image", strFilePath);

								try {
									FileOutputStream fos = new FileOutputStream(
											strFilePath);
									fos.write(imageBuffer, imagePtr + 36,
											imageLength - 36);
									fos.close();
								} catch (FileNotFoundException ex) {
									System.out
									.println("FileNotFoundException : "
											+ ex);
								} catch (IOException ioe) {
									System.out.println("IOException : " + ioe);
								}
								snapshot = false;
							}
						}
						if (newPtr > maxImageBuffer / 2) {
							// copy first chunk of new arrived image to start of
							// array
							for (int i = 0; i < len; i++)
								imageBuffer[i] = imageBuffer[newPtr + i];
							imagePtr = 0;
							tcpPtr = len;
						} else {
							imagePtr = newPtr;
							tcpPtr = newPtr + len;
						}
						if (bDebug) {
							Log.d("Var", "imagePtr =" + imagePtr);
							Log.d("Var", "tcpPtr =" + tcpPtr);
							Log.d("Var", "imageLength =" + imageLength);
							Log.d("Var", "newPtr =" + newPtr);
							Log.d("Var", "len =" + len);
						}
					}
				}
				// reset if ptr runs out of boundaries
				if (newPtr >= maxImageBuffer - maxTCPBuffer) {
					Log.w("ReceiveImage", "Out of index, should not happen!");
					imagePtr = 0;
					tcpPtr = 0;
				}
			} catch (Exception eg) {
				Log.e("ReceiveImage", "General input stream error", eg);
				eg.printStackTrace();
			}
		}

		public void WriteCmd(int index, byte[] extra_input) {
			int len = 0;
			switch (index) {
			case 1:
				len = 23;
				break;
			case 2:
				len = 49;
				break;
			case 3:
				len = 24;
				break;
			case 4:
				len = 27;
				break;
			case 5:
				len = 25;
				break; // cmd for the wheels
			case 6:
				len = 25;
				break;
			case 7:
				len = 25;
				break;
			case 8:
				len = 25;
				break;
			case 9:
				len = 23;
				break;
			case 10:
				len = 24;
				break;
			case 11:
				len = 24;
				break;
			}
			byte[] buffer = new byte[len];
			for (int i = 4; i < len; i++)
				buffer[i] = '\0';
			buffer[0] = 'M';
			buffer[1] = 'O';
			buffer[2] = '_';
			buffer[3] = 'O';
			if (index == 4) {
				buffer[3] = 'V';
			}

			switch (index) {
			case 1:
				break;
			case 2:
				buffer[4] = 0x02;
				buffer[15] = 0x1a;
				buffer[23] = 'A';
				buffer[24] = 'C';
				buffer[25] = '1';
				buffer[26] = '3';
				buffer[36] = 'A';
				buffer[37] = 'C';
				buffer[38] = '1';
				buffer[39] = '3';
				break;
			case 3:
				buffer[4] = 0x04;
				buffer[15] = 0x01;
				buffer[19] = 0x01;
				buffer[23] = 0x02;
				break;
			case 4:
				buffer[15] = 0x04;
				buffer[19] = 0x04;
				for (int i = 0; i < 4; i++)
					buffer[i + 23] = extra_input[i];
				break;
			case 5: // backwards, right
				buffer[4] = (byte) 0xfa;
				buffer[15] = 0x02;
				buffer[19] = 0x01;
				buffer[23] = 0x04;
				buffer[24] = (byte) 0x0a;
				break;
			case 6: // front, right
				buffer[4] = (byte) 0xfa;
				buffer[15] = 0x02;
				buffer[19] = 0x01;
				buffer[23] = 0x05;
				buffer[24] = (byte) 0x0a;
				break;
			case 7: // backwards, left
				buffer[4] = (byte) 0xfa;
				buffer[15] = 0x02;
				buffer[19] = 0x01;
				buffer[23] = 0x01;
				buffer[24] = (byte) 0x0a;
				break;
			case 8: // front, right
				buffer[4] = (byte) 0xfa;
				buffer[15] = 0x02;
				buffer[19] = 0x01;
				buffer[23] = 0x02;
				buffer[24] = (byte) 0x0a;
				break;
			case 9: // IR off(?)
				buffer[4] = (byte) 0xff;
				break;
			case 10:
				buffer[4] = (byte) 0x0e;
				buffer[15] = 0x01;
				buffer[19] = 0x01;
				buffer[23] = (byte)0x5e;
				break;
			case 11:
				buffer[4] = (byte) 0x0e;
				buffer[15] = 0x01;
				buffer[19] = 0x01;
				buffer[23] = (byte)0x5f;
				break;
				// buffer[15] = 0x02;
				// buffer[19] = 0x01;
				// buffer[23] = 0x02;
				// buffer[24] = (byte) 0x0a;
				// break;
			}

			if (bDebug) {
				String str = new String(buffer, 0, len);
				Log.d("Write i=" + index, str);
			}
			if (index >= 5) {
				String str = new String(buffer, 0, len);
				Log.i("Write i=" + index, str);
			}
			if (index != 4) {
				try {
					cSock.getOutputStream().write(buffer, 0, len);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				try {
					vSock.getOutputStream().write(buffer, 0, len);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

	}

}