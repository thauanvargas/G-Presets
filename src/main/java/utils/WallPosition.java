package utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WallPosition {

    private static final Pattern WALL_PATTERN = Pattern.compile(
            ":w=(\\d+),(\\d+)\\s+l=(-?\\d+),(-?\\d+)\\s+([rl])(?:\\s+a=(-?\\d+))?"
    );

    private int x;
    private int y;
    private int offsetX;
    private int offsetY;
    private char direction; // 'l' or 'r'
    private int altitude;

    public WallPosition(int x, int y, int offsetX, int offsetY, char direction, int altitude) {
        this.x = x;
        this.y = y;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.direction = direction;
        this.altitude = altitude;
    }

    public WallPosition(String location) {
        Matcher matcher = WALL_PATTERN.matcher(location);
        if (matcher.find()) {
            this.x = Integer.parseInt(matcher.group(1));
            this.y = Integer.parseInt(matcher.group(2));
            this.offsetX = Integer.parseInt(matcher.group(3));
            this.offsetY = Integer.parseInt(matcher.group(4));
            this.direction = matcher.group(5).charAt(0);
            this.altitude = matcher.group(6) != null ? Integer.parseInt(matcher.group(6)) : 0;
        } else {
            throw new IllegalArgumentException("Invalid wall position format: " + location);
        }
    }

    /**
     * Returns the Habbo protocol format (without altitude) for placing wall items.
     */
    @Override
    public String toString() {
        return String.format(":w=%d,%d l=%d,%d %c", x, y, offsetX, offsetY, direction);
    }

    /**
     * Returns the full format including altitude, used for preset storage.
     */
    public String toFullString() {
        return String.format(":w=%d,%d l=%d,%d %c a=%d", x, y, offsetX, offsetY, direction, altitude);
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getOffsetX() {
        return offsetX;
    }

    public int getOffsetY() {
        return offsetY;
    }

    public char getDirection() {
        return direction;
    }

    public int getAltitude() {
        return altitude;
    }
}
