/*
 * Created by Daniel Marell 2011-09-13 23:34
 */
package se.marell.iodevices.ontrak;

import se.marell.dcommons.time.PassiveTimer;

public class Adu208AdutuxTestApp {
  public static void main(String[] args) {
//    for (int i = 0; i < 100; ++i) {
//      runBurst(100);
//      System.out.println("Burst " + i);
//    }
      runBurst(10000);
  }

  public static void runBurst(long time) {
    Adu208Adutux device = new Adu208Adutux(0);
    device.requestGetDigitalInputs();

    int doValue = 0;
    int diValue = 0;
    PassiveTimer exitTimer = new PassiveTimer(time);
    PassiveTimer printTimer = new PassiveTimer(200);
    int diCount = 0;
    int loopCount = 0;
    for (;;) {
      device.requestSetDigitalOutputs(doValue);

      //doValue = ((doValue & 2) == 0 ? 2 : 0);// Flip relay 2(8)

      // Let input 1(8) control relay 1(8)
      boolean input = (diValue & 1) != 0;
      if (input) {
        doValue |= 1;
      } else {
        doValue &= ~1;
      }

      Integer di = device.getDigitalInputs();
      if (di != null) {
        device.requestGetDigitalInputs();
        diValue = di;
        diCount++;
      }
      loopCount++;

      if (printTimer.hasExpired()) {
        System.out.printf("diValue=%02X loopCount=%d diCount=%d status=%s\n", diValue, loopCount, diCount, getStatus(device));
        printTimer.restart();
        loopCount = 0;
        diCount = 0;
      }

      try {
        Thread.sleep(1);
      } catch (InterruptedException ignore) {
      }

      if (exitTimer.hasExpired()) {
        System.out.println("Stopping...");
        device.stop();
        System.out.println("Stopped.");
        return;
      }
    }
  }

  private static String getStatus(Adu208Adutux device) {
    return "connected=" + device.isConnected();
  }
}
