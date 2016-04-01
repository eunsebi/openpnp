package org.openpnp.machine.reference.driver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceDriver;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceHeadMountable;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.spi.base.SimplePropertySheetHolder;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GcodeDriver extends AbstractSerialPortDriver implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(GcodeDriver.class);

    @Attribute(required = false)
    protected LengthUnit units = LengthUnit.Millimeters;

    @Attribute(required = false)
    protected int maxFeedRate = 1000;

    @Element(required=false)
    protected String commandConfirmRegex = "^ok.*";

    @Element(required=false)
    protected String connectCommand = "G21\nG90\nM82";

    @Element(required=false)
    protected String enableCommand = "M810";

    @Element(required=false)
    protected String disableCommand = "M84\nM811";

    @Element(required=false)
    protected String homeCommand = "M84\nG4P500\nG28 X0 Y0\nG92 X0 Y0 Z0 E0";

    /**
     * This command has special handling for the X, Y, Z and Rotation variables. If the
     * move does not change one of these variables that variable is replaced with the empty
     * string, removing it from the command. This allows Gcode to be sent containing only
     * the components that are being used which is important for some controllers when
     * moving an "extruder" for the C axis. The end result is that if a move contains
     * only a change in the C axis only the C axis value will be sent.
     */
    @Element(required=false)
    protected String moveToCommand =
            "G0{X:X%.4f}{Y:Y%.4f}{Z:Z%.4f}{Rotation:E%.4f}F{FeedRate:%.0f}\nM400";

    @Element(required=false)
    protected String pickCommand = "M3";

    @Element(required=false)
    protected String placeCommand = "M5";

    @Element(required=false)
    protected String actuateBooleanCommand = "G4S1";

    @Element(required=false)
    protected String actuateDoubleCommand = "G4S1";

    @Element(required=false)
    protected String syncCommand = "M114";

    @Element(required=false)
    protected String syncRegex = ".*X:.*Y:.*";
    
    @ElementList(required=false)
    protected List<ReferenceDriver> subDrivers = new ArrayList<>();

    protected double x, y, z, c;
    private Thread readerThread;
    private boolean disconnectRequested;
    private boolean connected;
    private LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

    public synchronized void connect() throws Exception {
        super.connect();

        connected = false;
        List<String> responses;
        readerThread = new Thread(this);
        readerThread.start();

        responses = sendGcode(syncCommand, 5000);
        long t = System.currentTimeMillis();
        // Check for the correct response for up to 5 seconds.
        while (System.currentTimeMillis() - t < 5000) {
            for (String response : responses) {
                if (response.matches(syncRegex)) {
                    connected = true;
                    break;
                }
            }
            if (connected) {
                break;
            }
            responses = sendCommand(null, 200);
        }

        if (!connected) {
            throw new Exception(String.format(
                    "Unable to receive connection response. Check your port and baud rate"));
        }

        // Turn off the stepper drivers
        setEnabled(false);

        // Send startup Gcode
        sendGcode(connectCommand);
    }

    @Override
    public void setEnabled(boolean enabled) throws Exception {
        if (enabled && !connected) {
            connect();
        }
        if (connected) {
            if (enabled) {
                sendGcode(enableCommand);
            }
            else {
                sendGcode(disableCommand);
            }
        }
        
        for (ReferenceDriver driver : subDrivers) {
            driver.setEnabled(enabled);
        }
    }

    @Override
    public void home(ReferenceHead head) throws Exception {
        // Home is sent with an infinite timeout since it's tough to tell how long it will
        // take.
        sendGcode(homeCommand, -1);
        
        for (ReferenceDriver driver : subDrivers) {
            driver.home(head);
        }
    }

    @Override
    public Location getLocation(ReferenceHeadMountable hm) {
        Location location = new Location(units, x, y, z, c).add(hm.getHeadOffsets());
        if (!(hm instanceof Nozzle)) {
            location = location.derive(null, null, 0d, null);
        }
        return location;
    }

    @Override
    public void moveTo(ReferenceHeadMountable hm, Location location, double speed)
            throws Exception {
        location = location.convertToUnits(units);

        location = location.subtract(hm.getHeadOffsets());

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        double c = location.getRotation();

        // Only move Z if it's the Nozzle.
        if (!(hm instanceof Nozzle)) {
            z = Double.NaN;
        }

        // Handle NaNs, which means don't move this axis for this move.
        if (Double.isNaN(x)) {
            x = this.x;
        }
        if (Double.isNaN(x)) {
            y = this.y;
        }
        if (Double.isNaN(x)) {
            z = this.z;
        }
        if (Double.isNaN(x)) {
            c = this.c;
        }

        String command = moveToCommand;
        command = substituteVariable(command, "X", x == this.x ? null : x);
        command = substituteVariable(command, "Y", y == this.y ? null : y);
        command = substituteVariable(command, "Z", z == this.z ? null : z);
        command = substituteVariable(command, "Rotation", c == this.c ? null : c);
        command = substituteVariable(command, "FeedRate", maxFeedRate * speed);
        sendGcode(command);

        this.x = x;
        this.y = y;
        this.z = z;
        this.c = c;
        
        for (ReferenceDriver driver : subDrivers) {
            driver.moveTo(hm, location, speed);
        }
    }

    @Override
    public void pick(ReferenceNozzle nozzle) throws Exception {
        sendGcode(pickCommand);
        
        for (ReferenceDriver driver : subDrivers) {
            driver.pick(nozzle);
        }
    }

    @Override
    public void place(ReferenceNozzle nozzle) throws Exception {
        sendGcode(placeCommand);
        
        for (ReferenceDriver driver : subDrivers) {
            driver.place(nozzle);
        }
    }


    @Override
    public void actuate(ReferenceActuator actuator, boolean on) throws Exception {
        String command = actuateBooleanCommand;
        command = substituteVariable(command, "Name", actuator.getName());
        command = substituteVariable(command, "Index", actuator.getIndex());
        command = substituteVariable(command, "BooleanValue", on);
        sendGcode(command);
        
        for (ReferenceDriver driver : subDrivers) {
            driver.actuate(actuator, on);
        }
    }

    @Override
    public void actuate(ReferenceActuator actuator, double value) throws Exception {
        String command = actuateDoubleCommand;
        command = substituteVariable(command, "Name", actuator.getName());
        command = substituteVariable(command, "Index", actuator.getIndex());
        command = substituteVariable(command, "DoubleValue", value);
        sendGcode(command);
        
        for (ReferenceDriver driver : subDrivers) {
            driver.actuate(actuator, value);
        }
    }

    public synchronized void disconnect() {
        disconnectRequested = true;
        connected = false;

        try {
            if (readerThread != null && readerThread.isAlive()) {
                readerThread.join();
            }
        }
        catch (Exception e) {
            logger.error("disconnect()", e);
        }

        try {
            super.disconnect();
        }
        catch (Exception e) {
            logger.error("disconnect()", e);
        }
        disconnectRequested = false;
    }

    @Override
    public void close() throws IOException {
        super.close();
        
        for (ReferenceDriver driver : subDrivers) {
            driver.close();
        }
    }
    
    protected List<String> sendGcode(String gCode) throws Exception {
        return sendGcode(gCode, 5000);
    }

    protected List<String> sendGcode(String gCode, long timeout) throws Exception {
        if (gCode == null) {
            return new ArrayList<>();
        }
        List<String> responses = new ArrayList<>();
        for (String command : gCode.split("\n")) {
            command = command.trim();
            responses.addAll(sendCommand(command));
        }
        return responses;
    }

    protected List<String> sendCommand(String command) throws Exception {
        return sendCommand(command, 5000);
    }

    protected List<String> sendCommand(String command, long timeout) throws Exception {
        List<String> responses = new ArrayList<>();

        // Read any responses that might be queued up so that when we wait
        // for a response to a command we actually wait for the one we expect.
        responseQueue.drainTo(responses);

        logger.debug("sendCommand({}, {})", command, timeout);

        // Send the command, if one was specified
        if (command != null) {
            logger.debug(">> " + command);
            output.write(command.getBytes());
            output.write("\n".getBytes());
        }

        // Collect responses till we find one with ok or error or we timeout. Return
        // the collected responses.
        if (timeout == -1) {
            timeout = Long.MAX_VALUE;
        }
        long t = System.currentTimeMillis();
        boolean found = false;
        // Loop until we've timed out
        while (System.currentTimeMillis() - t < timeout) {
            // Wait to see if a response came in. We wait up until the number of seconds remaining
            // in the timeout.
            String response =
                    responseQueue.poll(System.currentTimeMillis() - t, TimeUnit.MILLISECONDS);
            // If no response yet, try again.
            if (response == null) {
                continue;
            }
            // Store the response that was received
            responses.add(response);
            // If the response is an ok or error we're done
            if (response.matches(commandConfirmRegex)) {
                found = true;
                break;
            }
        }
        // If no ok or error response was found it's an error
        if (!found) {
            throw new Exception("Timeout waiting for response to " + command);
        }

        // Read any additional responses that came in after the initial one.
        responseQueue.drainTo(responses);

        logger.debug("{} => {}", command, responses);
        return responses;
    }

    public void run() {
        while (!disconnectRequested) {
            String line;
            try {
                line = readLine().trim();
            }
            catch (TimeoutException ex) {
                continue;
            }
            catch (IOException e) {
                logger.error("Read error", e);
                return;
            }
            line = line.trim();
            logger.debug("<< " + line);
            responseQueue.offer(line);
        }
    }

    /**
     * Find matches of variables in the format {Name:Format} and replace them with the specified
     * value formatted using String.format with the specified Format. Format is optional and
     * defaults to %s. A null value replaces the variable with "".
     */
    static protected String substituteVariable(String command, String name, Object value) {
        if (command == null) {
            return command;
        }
        StringBuffer sb = new StringBuffer();
        Matcher matcher = Pattern.compile("\\{(\\w+)(?::(.+?))?\\}").matcher(command);
        while (matcher.find()) {
            String n = matcher.group(1);
            if (!n.equals(name)) {
                continue;
            }
            String format = matcher.group(2);
            if (format == null) {
                format = "%s";
            }
            String v = "";
            if (value != null) {
                v = String.format(format, value);
            }
            matcher.appendReplacement(sb, v);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        ArrayList<PropertySheetHolder> children = new ArrayList<>();
        if (!subDrivers.isEmpty()) {
            children.add(new SimplePropertySheetHolder("Sub-Drivers", subDrivers));
        }
        return children.toArray(new PropertySheetHolder[] {});
    }
}