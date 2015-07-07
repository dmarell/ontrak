/*
 * Created by Daniel Marell 2011-09-13 20:49
 */
package se.marell.iodevices.ontrak;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class Adu208AdutuxRawDeviceTestApp {

  private static void sleep(int ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private static boolean writeString(RandomAccessFile raf, String s) {
    byte[] arr = new byte[8];
    for (int i = 0; i < s.length(); ++i) {
      arr[i] = (byte) s.charAt(i);
    }
    try {
      System.out.println("                               Write=" + s + ": " + new String(arr, 1, s.length() - 1));
      raf.write(arr);
      return true;
    } catch (IOException e) {
      System.out.println("Write failed:" + e.getMessage());
      return false;
    }
  }

  private static boolean readAndPrintString(RandomAccessFile raf) {
    FileChannel inChannel = raf.getChannel();
    ByteBuffer buf = ByteBuffer.allocate(8);
//    byte[] arr = new byte[8];
    try {
      int n = inChannel.read(buf);

      String s = "";
      for (int i = 0; i < n; ++i) {
        s += String.format("%02x ", buf.get(i));
      }
      System.out.println("Read=" + s + ": " + buf.toString());
      return true;
    } catch (IOException e) {
      System.out.println("Read failed:" + e.getMessage());
      return false;
    }
  }

  public static void main(String[] args) {
    boolean doOpen = true;
    int i = 0;
    RandomAccessFile raf = null;
    for (; ; ) {

      if (doOpen) {
        if (raf != null) {
          try {
            raf.close();
          } catch (IOException e) {
            System.out.println("Failed to close file:" + e.getMessage());
          }
        }
        try {
          raf = new RandomAccessFile(new File("/dev/adutux0"), "rws");
          doOpen = false;
          System.out.println("Opened file");
        } catch (FileNotFoundException e) {
          System.out.println("Failed to open file:" + e.getMessage());
        }
      }

      System.out.println("Loop=" + i);
      ++i;

      if (!doOpen) {
        if (!writeString(raf, "\001MK0")) {
          doOpen = true;
        }
        sleep(100);
        if (!writeString(raf, "\001MK1")) {
          doOpen = true;
        }
        sleep(100);
        if (!writeString(raf, "\001PI")) {
          doOpen = true;
        }
        sleep(100);
        if (!readAndPrintString(raf)) {
          doOpen = true;
        }
      }
    }
  }
}
