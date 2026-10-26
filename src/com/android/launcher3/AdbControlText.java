package com.android.launcher3;

import android.content.Intent;
import android.database.Cursor;

import java.util.Locale;

/**
 * v104-Java: the two pure text helpers of the AI control channels, moved out of
 * hand-written smali (AdbControlReceiver) into ordinary Java. They compile against
 * the platform jar only - no launcher internals, no Context - so they can live in
 * mod-src and reach the app through the javac/d8/baksmali pipeline.
 *
 * Behaviour is byte-for-byte the v113 contract the regression bench asserts, so the
 * five call sites that used to run the smali now run this instead with no change in
 * outcome:
 *
 *   namedPkg   - "what a package name is": trim, and turn blank/empty/null into
 *                null so every caller reports the same "need package_name".
 *   likePattern - "%" + name + "/%" with each LIKE metacharacter ( % _ \ )
 *                backslash-escaped, so a name is matched as plain text only. The
 *                query using the result must say ESCAPE '\'.
 *   row         - one favorites Cursor row -> the "ICON|id=..|title=..|.." text line.
 *                Only StringBuilder and android.database.Cursor (both platform), so
 *                the read-side formatting lives here; the Cursor itself is opened by
 *                the launcher-coupled scanner, which stays smali.
 *   absentText  - the "absent: <pkg> has no workspace item" reply for a stateOf that
 *                found no row.
 */
public final class AdbControlText {

    private AdbControlText() {
    }

    /**
     * Resolve and normalize the command of an intent, exactly as the smali executor
     * used to: the string extra "cmd", else the last dotted segment of the action
     * (a full action is kept when it has no dot), folded to lower case with
     * Locale.ROOT - not the device language, so a Turkish device cannot turn
     * LIST_ICONS / MOVE_ICON into a dotless-i spelling and silently kill the
     * shortcut channel. null means "no command given".
     */
    public static String cmdFrom(Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) {
            cmd = intent.getAction();
            if (cmd != null) {
                int dot = cmd.lastIndexOf('.');
                if (dot >= 0) {
                    cmd = cmd.substring(dot + 1);
                }
            }
        }
        if (cmd != null) {
            cmd = cmd.toLowerCase(Locale.ROOT);
        }
        return cmd;
    }

    /** Trimmed package name, or null when the caller gave nothing usable. */
    public static String namedPkg(String raw) {
        if (raw != null) {
            String trimmed = raw.trim();
            if (trimmed.length() > 0) {
                return trimmed;
            }
        }
        return null;
    }

    /** "%" + escaped(raw) + "/%", with % _ and \ each prefixed by a backslash. */
    public static String likePattern(String raw) {
        StringBuilder sb = new StringBuilder();
        sb.append('%');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('/');
        sb.append('%');
        return sb.toString();
    }

    /**
     * Append the "ICON|..." line for one favorites row to the builder, reading the
     * eight projected columns by position (0 _id, 1 title, 2 intent, 3 container,
     * 4 screen, 5 cellX, 6 cellY, 7 itemType). A null title/intent column appends
     * "null" exactly as the smali append(String) did. No newline: the caller adds it.
     */
    public static void row(StringBuilder sb, Cursor c) {
        sb.append("ICON|id=");
        sb.append(c.getInt(0));
        sb.append("|title=");
        sb.append(c.getString(1));
        sb.append("|intent=");
        sb.append(c.getString(2));
        sb.append("|container=");
        sb.append(c.getInt(3));
        sb.append("|screen=");
        sb.append(c.getInt(4));
        sb.append("|x=");
        sb.append(c.getInt(5));
        sb.append("|y=");
        sb.append(c.getInt(6));
        sb.append("|type=");
        sb.append(c.getInt(7));
    }

    /** The reply when a package has no workspace row at all. */
    public static String absentText(String pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("absent: ");
        sb.append(pkg);
        sb.append(" has no workspace item");
        return sb.toString();
    }
}
