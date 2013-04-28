/*
 	Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 	
 	This file is part of OpenPnP.
 	
	OpenPnP is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenPnP is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenPnP.  If not, see <http://www.gnu.org/licenses/>.
 	
 	For more information about OpenPnP visit http://openpnp.org
 *
 *  Ami: Driver for LinuxCNC.
 Created 28/09/2012. Setup in machine.xml
 * This is quick-n-dirty driver, it works but lots of setup gui are not done.
 * I'm relying on linuxCNC to do the hardware setup (homing etc) and just when it's
 * ready to run, then the OpenPNP can take over.
 */

package org.openpnp.machine.reference.driver;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.openpnp.ConfigurationListener;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceDriver;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceHeadMountable;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.simpleframework.xml.Attribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: Consider adding some type of heartbeat to the firmware.
 */
public class LinuxCNC implements ReferenceDriver, Runnable {
    private static final Logger logger = LoggerFactory
            .getLogger(LinuxCNC.class);
    private static final double minimumRequiredVersion = 0.81;

    @Attribute
    private String serverIp;
    @Attribute
    private int port;
    @Attribute
    private double feedRateMmPerMinute;

    private double x, y, z, c;
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private Thread readerThread;
    private boolean disconnectRequested;
    private Object commandLock = new Object();
    private boolean connected;
    private double connectedVersion;
    private Queue<String> responseQueue = new ConcurrentLinkedQueue<String>();
    private final static int CONNECT_TIMOUT = 5; // 5 second time-out for
                                                 // connection

    private static Scanner in;
    private static PrintWriter out;
    
    public LinuxCNC() {
        Configuration.get().addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationComplete(Configuration configuration)
                    throws Exception {
                connect(serverIp, port);
            }
        });
    }
    
    @Override
    public void home(ReferenceHead head) throws Exception {
        sendCommand("set mdi G0 Z-20"); // SafeZ
        sendCommand("set mdi G0 X0 Y0");
        sendCommand("set mdi G1 F200 Z0");
        x = y = z = c = 0;
    }



    @Override
    public void moveTo(ReferenceHeadMountable hm, Location location,
            double speed) throws Exception {
        location = location.subtract(hm.getHeadOffsets());

        location = location.convertToUnits(LengthUnit.Millimeters);
        
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        double c = location.getRotation();
        
        StringBuffer sb = new StringBuffer();
        if (!Double.isNaN(x) && x != this.x) {
            sb.append(String.format(Locale.US, "X%2.2f ", x));
        }
        if (!Double.isNaN(y) && y != this.y) {
            sb.append(String.format(Locale.US, "Y%2.2f ", y));
        }
        if (!Double.isNaN(z) && z != this.z) {
            sb.append(String.format(Locale.US, "Z%2.2f ", z));
        }
        if (!Double.isNaN(c) && c != this.c) {
            sb.append(String.format(Locale.US, "A%2.2f ", c));
        }
        if (sb.length() > 0) {
            sb.append(String.format(Locale.US, "F%2.2f", feedRateMmPerMinute * speed));
            sendCommand("set mdi G1 " + sb.toString());
            dwell();
        }
        this.x = x;
        this.y = y;
        this.z = z;
        this.c = c;
    }



    @Override
    public Location getLocation(ReferenceHeadMountable hm) {
        return new Location(LengthUnit.Millimeters, x, y, z, c);
    }



    @Override
    public void pick(ReferenceNozzle nozzle) throws Exception {
        sendCommand("set mdi m3 s100");
        dwell();
    }



    @Override
    public void place(ReferenceNozzle nozzle) throws Exception {
        sendCommand("set mdi m5");
        dwell();
    }



    @Override
    public void actuate(ReferenceActuator actuator, boolean on)
            throws Exception {
        // if (index == 0) {
        // sendCommand(on ? "M8" : "M9");
        // dwell();
        // }
    }



    @Override
    public void actuate(ReferenceActuator actuator, double value)
            throws Exception {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void setEnabled(boolean enabled) throws Exception {

        sendCommand("set machine " + (enabled ? "on" : "off"));
    }

    public synchronized void connect(String serverIp, int port)
            throws Exception {
        // disconnect();
        logger.debug("connect({}, {})", serverIp, port);
        SocketAddress sa = new InetSocketAddress(serverIp, port);
        socket = new Socket();
        socket.connect(sa, CONNECT_TIMOUT * 1000);
        input = socket.getInputStream();
        output = socket.getOutputStream();

        List<String> responses;
        synchronized (commandLock) {
            // Start the reader thread with the commandLock held. This will
            // keep the thread from quickly parsing any responses messages
            // and notifying before we get a change to wait.
            readerThread = new Thread(this);
            readerThread.start();
            // Wait up to 3 seconds for Grbl to say Hi
            // If we get anything at this point it will have been the settings
            // dump that is sent after reset.
            // responses = sendCommand(null, 3000);
        }
        connected = true;

        responses = sendCommand("hello EMC x 1");
        // responses.addAll(sendCommand("set echo off"));
        responses.addAll(sendCommand("set enable EMCTOO"));

        responses.addAll(sendCommand("set estop off"));
        responses.addAll(sendCommand("set mode mdi"));

        processConnectionResponses(responses);

        if (!connected) {
            throw new Error(
                    "Unable to receive connection response from LinuxCNC ver 1.1. Check your server ip and port in machine.xml");
        }

        if (!connected) {
            throw new Error(
                    String.format(
                            "Unable to receive connection response from LinuxCNC. Check your server ip and port in machine.xml and that you are running at least version %f of LinuxCNCrsh",
                            minimumRequiredVersion));
        }

        if (connectedVersion < minimumRequiredVersion) {
            throw new Error(
                    String.format(
                            "This driver requires LinuxCNCrsh version %.2f or higher. You are running version %.2f",
                            minimumRequiredVersion, connectedVersion));
        }

        // We are connected to at least the minimum required version now
        // So perform some setup

        // Turn off the stepper drivers
        setEnabled(false);

        // Reset all axes to 0, in case the firmware was not reset on
        // connect.
        sendCommand("set mdi G92 X0 Y0 Z0 A0");
    }

    private void processConnectionResponses(List<String> responses) {
        for (String response : responses) {
            if (response.startsWith("HELLO ACK EMCNETSVR 1.1")) {

                connectedVersion = 1.1;
                connected = true;
                logger.debug(String.format(
                        "Connected to LinuxCNCrsh Version: %.2f",
                        connectedVersion));
            }
        }
    }

    public synchronized void disconnect() {
        disconnectRequested = true;
        connected = false;

        try {
            if (readerThread != null && readerThread.isAlive()) {
                readerThread.join();
            }
            input.close();
            output.close();

            socket.close();
        }
        catch (Exception e) {
            logger.error("disconnect()", e);
        }

        disconnectRequested = false;
    }

    private List<String> sendCommand(String command) throws Exception {
        return sendCommand(command, -1);
    }

    private List<String> sendCommand(String command, long timeout)
            throws Exception {
        synchronized (commandLock) {
            if (command != null) {
                logger.debug("sendCommand({}, {})", command, timeout);
                output.write(command.getBytes());
                output.write("\r\n".getBytes());

            }
            if (timeout == -1) {
                commandLock.wait();
            }
            else {
                commandLock.wait(timeout);
            }
        }
        List<String> responses = drainResponseQueue();
        return responses;
    }

    public void run() {
        while (!disconnectRequested) {
            String line = readLine().trim();
            logger.debug(line);
            responseQueue.offer(line);
            synchronized (commandLock) {
                commandLock.notify();

            }
        }
    }

    /**
     * Causes Grbl to block until all commands are complete.
     * 
     * @throws Exception
     */
    private void dwell() throws Exception {
        sendCommand("set mdi G4 P0");
    }

    private List<String> drainResponseQueue() {
        List<String> responses = new ArrayList<String>();
        String response;
        while ((response = responseQueue.poll()) != null) {
            responses.add(response);
        }
        return responses;
    }

    private String readLine() {
        StringBuffer line = new StringBuffer();
        try {
            while (true) {
                int ch = readChar();
                if (ch == -1) {
                    return null;
                }
                else if (ch == '\n' || ch == '\r') {
                    if (line.length() > 0) {
                        return line.toString();
                    }
                }
                else {
                    line.append((char) ch);
                }
            }
        }
        catch (Exception e) {
            logger.error("readLine()", e);
        }
        return null;
    }

    private int readChar() {
        try {
            int ch = -1;
            while (ch == -1 && !disconnectRequested) {
                ch = input.read();
            }
            return ch;
        }
        catch (Exception e) {
            logger.error("readChar()", e);
            return -1;
        }
    }
}