## ontrak

``ontrak`` is a library containing support classes for [Ontrak Systems](http://www.ontrak.net/) I/O devices.

The library is packaged as an OSGi bundle.

### Release notes
* Version 1.0.4 - 2015-07-07 Moved to Github
* Version 1.0.3 - 2014-02-08
  * Java 7
  * Changed pom versioning mechanism.
  * Extended site information.
  * Updated versions of dependencies
* Version 1.0 - 2011-10-26 First version.

### Supported devices

In this version a single device is supported on a single platform: The ADU 208 model using the Linux
[adutux kernel driver module](http://www.ontrak.net/ADU208.htm).

### Maven usage

```
<repositories>
  <repository>
    <id>marell</id>
    <url>http://marell.se/nexus/content/repositories/releases/</url>
  </repository>
</repositories>

<dependency>
  <groupId>se.marell.iodevices</groupId>
  <artifactId>ontrak</artifactId>
  <version>1.0.4</version>
</dependency>
```

### ADU 208 installation

First, verify that your device is recognized:

```
$ tail -f /var/log/messages
```

Connect the ADU 208. The output the log should be something similar to this:

```
...
Oct 26 00:07:33 spitfire kernel: [1546487.661427] usb 2-6.3: new low speed USB device using ehci_hcd and address 20
Oct 26 00:07:33 spitfire kernel: [1546487.777112] usb 2-6.3: configuration #1 chosen from 1 choice
Oct 26 00:07:33 spitfire kernel: [1546487.780419] adutux 2-6.3:1.0: ADU208 C02134 now attached to /dev/usb/adutux0
...
```

Note that the device name reported in the log is incorrect. The real device name is ``/dev/adutux0``.
You have to arrange for setting permissions on the device file in order to be able to open it: Either
by controlling it with a group permission (I recommend that) or making it accessible to anyone:

```
$ sudo chmod 666 /dev/adutux0
```

### ADU 208 example usage

The class ``Adu208Adutux`` spawns a thread in it's constructor which dispatches requests. The usage pattern is
to first request reading of inputs or writing outputs, wait for the dispatch thread to handle requests and drop
by at a later time and pick up intput data and/or set new output data. There is no notification nor callback
mechanism because the purpose of this class is to incorporate the ADU 208 in an execution loop with a fixed
frequency.

The below method ``runBurst`` below runs in a tight loop (for ``time`` msec in order to spare the relay)
and copies input 1(8) to output relay 1(8) and flips relay 2(8).
It prints the status of inputs and outputs every 200 ms.

```
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

    doValue = ((doValue & 2) == 0 ? 2 : 0); // Flip relay 2(8)

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
      Thread.sleep(100);
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
```
