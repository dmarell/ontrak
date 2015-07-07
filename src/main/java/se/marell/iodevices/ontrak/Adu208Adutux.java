/*
 * Copyright (c) 2011 Daniel Marell
 * All rights reserved.
 *
 * Permission is hereby granted, free  of charge, to any person obtaining
 * a  copy  of this  software  and  associated  documentation files  (the
 * "Software"), to  deal in  the Software without  restriction, including
 * without limitation  the rights to  use, copy, modify,  merge, publish,
 * distribute,  sublicense, and/or sell  copies of  the Software,  and to
 * permit persons to whom the Software  is furnished to do so, subject to
 * the following conditions:
 *
 * The  above  copyright  notice  and  this permission  notice  shall  be
 * included in all copies or substantial portions of the Software.
 *
 * THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
 * EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
 * MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package se.marell.iodevices.ontrak;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.marell.dcommons.time.PassiveTimer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Controller for ADU 208 I/O device from Ontrak Systems model using the Linux adutux kernel driver module.
 */
public class Adu208Adutux {
  public enum DebounceTime {
    DEBOUNCE_10_MS(0),
    DEBOUNCE_1_MS(1),
    DEBOUNCE_100_US(2);

    private int value;

    DebounceTime(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  private static final int NUM_INPUT_BITS = 8;

  protected final Logger log = LoggerFactory.getLogger(this.getClass());
  private static final String DEVICE_NAME_PREFIX = "/dev/adutux";
  private int deviceNumber;
  private String deviceName;
  private boolean connected;
  private long timestampLatestContact;
  private Integer doStatus;
  private Integer diStatus;
  private Integer[] eventCounters = new Integer[NUM_INPUT_BITS];
  private boolean[] requestEventCounter = new boolean[NUM_INPUT_BITS];
  private boolean[] requestAndResetEventCounter = new boolean[NUM_INPUT_BITS];
  private DebounceTime[] requestSetEventCounterDebounce = new DebounceTime[NUM_INPUT_BITS];
  private RandomAccessFile raf;
  private PassiveTimer reconnectTimer = new PassiveTimer(1000);
  private Thread dispatchThread;

  public Adu208Adutux(int deviceNumber) {
    this(DEVICE_NAME_PREFIX + deviceNumber, deviceNumber);
  }

  public Adu208Adutux(String deviceName, int deviceNumber) {
    this.deviceName = deviceName;
    this.deviceNumber = deviceNumber;
    reconnectTimer.forceExpire();
    dispatchThread = new Thread(new Runnable() {
      @Override
      public void run() {
        dispatchLoop();
      }
    }, "Adu208Adutux-dispatch");
    dispatchThread.start();
  }

  public int getDeviceNumber() {
    return deviceNumber;
  }

  public String getDeviceName() {
    return deviceName;
  }

  public synchronized void requestSetDigitalOutputs(int value) {
    doStatus = value;
    notify();
  }

  public synchronized void requestGetDigitalInputs() {
    diStatus = null;
    notify();
  }

  public synchronized void requestGetEventCounter(int cn) {
    requestEventCounter[cn] = true;
    eventCounters[cn] = null;
    notify();
  }

  public synchronized void requestGetAndResetEventCounter(int cn) {
    requestAndResetEventCounter[cn] = true;
    eventCounters[cn] = null;
    notify();
  }

  public synchronized void requestSetEventCounterDebounce(int cn, DebounceTime d) {
    requestSetEventCounterDebounce[cn] = d;
    notify();
  }

  public synchronized Integer getDigitalInputs() {
    return diStatus;
  }

  public synchronized Integer getEventCounter(int cn) {
    return eventCounters[cn];
  }

  public synchronized boolean isConnected() {
    return connected;
  }

  public synchronized long getTimestampLatestContact() {
    return timestampLatestContact;
  }

  private synchronized boolean connect() {
    if (!connected && reconnectTimer.hasExpired()) {
      try {
        raf = new RandomAccessFile(new File(deviceName), "rws");
        reconnectTimer.forceExpire();
        log.info("Opened device " + deviceName);
        return true;
      } catch (FileNotFoundException e) {
        connected = false;
        raf = null;
        log.info("Failed to open device " + deviceName + ":" + e.getMessage());
        reconnectTimer.restart();
        return false;
      }
    }
    return connected;
  }

  private boolean writeString(String s) {
    if (!connect()) {
      return false;
    }
    byte[] bytes = new byte[s.length()];
    for (int i = 0; i < s.length(); ++i) {
      bytes[i] = (byte) s.charAt(i);
    }
    try {
      raf.write(bytes);
      connected = true;
      timestampLatestContact = System.currentTimeMillis();
      log.trace("device:" + deviceName + ",wrote " + s);
      return true;
    } catch (IOException e) {
      deviceLost("Write failed", e);
      return false;
    }
  }

  private String readString() {
    if (!connect()) {
      return null;
    }
    byte[] bytes = new byte[8];
    try {
      int n = raf.read(bytes);
      for (int i = 0; i < n; ++i) {
        if (bytes[i] == 0) {
          n = i;
          break;
        }
      }
      String reply = new String(bytes, 1, n - 1);
      log.trace("device:" + deviceName + ",read " + reply);
      return reply;
    } catch (IOException e) {
      deviceLost("Read failed", e);
      return null;
    }
  }

  private void deviceLost(String s, IOException e) {
    log.warn(s + ":device " + deviceName + ":" + e.getMessage());
    connected = false;
    try {
      raf.close();
    } catch (IOException ignore) {
    }
    raf = null;
  }

  public void stop() {
    synchronized (this) {
      dispatchThread.interrupt();
      notifyAll();
      //Todo fix this the right way
//      while (dispatchThread != null) {
//        try {
//          wait();
//        } catch (InterruptedException ignore) {
//        }
//      }
      if (raf != null) {
        try {
          raf.close();
        } catch (IOException ignore) {
        }
        raf = null;
      }
    }
  }

  private void dispatchLoop() {
    while (true) {
      synchronized (this) {
        if (dispatchThread.isInterrupted()) {
          dispatchThread = null;
          notifyAll();
          return;
        }

        if (!commandPresent()) {
          try {
            wait();
          } catch (InterruptedException e) {
            dispatchThread = null;
            notifyAll();
            return;
          }
        }
      }

      // Dispatch digital outputs
      Integer tempDoStatus;
      synchronized (this) {
        tempDoStatus = doStatus;
      }
      if (tempDoStatus != null) {
        if (writeString("\001MK" + tempDoStatus)) {
          synchronized (this) {
            if (tempDoStatus.equals(doStatus)) {
              doStatus = null;
            }
          }
        }
      }

      // Dispatch digital inputs
      Integer tempDiStatus;
      synchronized (this) {
        tempDiStatus = diStatus;
      }
      if (tempDiStatus == null) {
        if (writeString("\001PI")) {
          String reply = readString();
          if (reply != null) {
            try {
              synchronized (this) {
                diStatus = Integer.parseInt(reply);
              }
            } catch (NumberFormatException e) {
              log.warn("Unexpected reply for command PI:" + reply + ":device " + deviceName + ":" + e.getMessage());
            }
          }
        }
      }

      // Dispatch request for get event counters
      for (int i = 0; i < NUM_INPUT_BITS; ++i) {
        boolean tempRequest;
        synchronized (this) {
          tempRequest = requestEventCounter[i];
        }
        if (tempRequest) {
          if (writeString("\001RE" + i)) {
            String reply = readString();
            if (reply != null) {
              try {
                synchronized (this) {
                  eventCounters[i] = Integer.parseInt(reply);
                  requestEventCounter[i] = false;
                }
              } catch (NumberFormatException e) {
                log.warn("Unexpected reply for command RE:" + reply + ":device " + deviceName + ":" + e.getMessage());
              }
            }
          }
        }
      }

      // Dispatch request for get and reset event counters
      for (int i = 0; i < NUM_INPUT_BITS; ++i) {
        boolean tempRequest;
        synchronized (this) {
          tempRequest = requestAndResetEventCounter[i];
        }
        if (tempRequest) {
          if (writeString("\001RC" + i)) {
            String reply = readString();
            if (reply != null) {
              try {
                synchronized (this) {
                  eventCounters[i] = Integer.parseInt(reply);
                  requestAndResetEventCounter[i] = false;
                }
              } catch (NumberFormatException e) {
                log.warn("Unexpected reply for command RC:" + reply + ":device " + deviceName + ":" + e.getMessage());
              }
            }
          }
        }
      }

      // Dispatch request for set debounce for event counters
      for (int i = 0; i < NUM_INPUT_BITS; ++i) {
        DebounceTime tempRequest;
        synchronized (this) {
          tempRequest = requestSetEventCounterDebounce[i];
        }
        if (tempRequest != null) {
          if (writeString("\001DB" + requestSetEventCounterDebounce[i].getValue())) {
            synchronized (this) {
              requestSetEventCounterDebounce[i] = null;
            }
          }
        }
      }

    }
  }

  private boolean commandPresent() {
    if (dispatchThread.isInterrupted()) {
      return true;
    }
    if (doStatus != null) {
      return true;
    }
    if (diStatus == null) {
      return true;
    }
    for (int i = 0; i < NUM_INPUT_BITS; ++i) {
      if (requestEventCounter[i] || requestAndResetEventCounter[i] || requestSetEventCounterDebounce[i] != null) {
        return true;
      }
    }
    return false;
  }
}
