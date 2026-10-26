package com.android.launcher3;

import android.content.Intent;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;

/**
 * v104-Java: the stateless HTTP/MCP request plumbing that used to be hand-written
 * smali in AdbControlHttp, rewritten in ordinary Java. Everything here only touches
 * the platform jar (java.io / java.net / android.content.Intent / android.util.Log)
 * - no launcher internals - so it compiles in mod-src and reaches the app through the
 * javac/d8/baksmali pipeline. AdbControlHttp keeps thin delegating stubs so every
 * existing caller still resolves to "AdbControlHttp.<name>"; the one method that does
 * need a launcher class (runOnMain, which posts AdbControlMainTask) stays smali.
 *
 * Each method reproduces the v110/v113 contract byte-for-byte, since the regression
 * bench asserts the exact wire behaviour of the HTTP and MCP channels:
 *
 *   readHeaders   - read one byte at a time until the \r\n\r\n tail, EOF, or 16 KiB.
 *   readBody      - read up to len bytes (capped at 256 KiB) so a bogus Content-Length
 *                   cannot pin megabytes of launcher heap.
 *   contentLength - the "content-length:" value, 0 when absent or unparsable.
 *   requestTarget - the "*" of "GET /cmd?... HTTP/1.1", "" when the line is malformed.
 *   param         - one decoded query parameter, or null; a key only matches at the
 *                   head or right after '&', so "?xpackage_name=a" is not read as
 *                   "package_name=a".
 *   applyQuery    - query string -> the extras handleCommand() reads.
 *   respond       - status line + two headers + body, then close.
 */
public final class AdbControlHttpText {

    private AdbControlHttpText() {
    }

    /** Read up to the blank line that ends the request headers (at most 16 KiB). */
    public static String readHeaders(InputStream in) {
        StringBuilder sb = new StringBuilder();
        try {
            while (true) {
                if (sb.length() >= 0x4000) {
                    break;
                }
                int c = in.read();
                if (c < 0) {
                    break;
                }
                sb.append((char) c);
                int len = sb.length();
                if (len >= 4
                        && sb.charAt(len - 4) == '\r'
                        && sb.charAt(len - 3) == '\n'
                        && sb.charAt(len - 2) == '\r'
                        && sb.charAt(len - 1) == '\n') {
                    break;
                }
            }
        } catch (Throwable t) {
            // a half read request is still returned as-is, exactly as the smali did
        }
        return sb.toString();
    }

    /** Read the body: at most {@code len} more bytes, returned as text. */
    public static String readBody(InputStream in, int len) {
        if (len <= 0) {
            return "";
        }
        // A client is free to claim any Content-Length, and reading all of it would let
        // one request pin megabytes of launcher heap. 256 KiB is far more than any
        // command or MCP frame needs - the rest of an oversized body is simply not read.
        int remaining = len > 0x40000 ? 0x40000 : len;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int bufSize = remaining < 0x1000 ? remaining : 0x1000;
        byte[] buf = new byte[bufSize];
        try {
            while (remaining > 0) {
                int r = in.read(buf, 0, remaining);
                if (r <= 0) {
                    break;
                }
                bos.write(buf, 0, r);
                remaining -= r;
            }
        } catch (Throwable t) {
            // whatever was read before the error is what we return
        }
        return bos.toString();
    }

    /** Content-Length of a request text, 0 when absent or unparsable. */
    public static int contentLength(String headers) {
        try {
            String low = headers.toLowerCase();
            int idx = low.indexOf("content-length:");
            if (idx < 0) {
                return 0;
            }
            idx += 15;
            int end = low.indexOf("\r\n", idx);
            if (end < 0) {
                return 0;
            }
            return Integer.parseInt(low.substring(idx, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** The "*" of "GET /cmd?cmd=list_icons HTTP/1.1", "" when the line is malformed. */
    public static String requestTarget(String request) {
        String ret = "";
        if (request != null) {
            int space = request.indexOf(' ');
            if (space >= 0) {
                int start = space + 1;
                int end = request.indexOf(' ', start);
                if (end < 0) {
                    end = request.indexOf('\r', start);
                    if (end < 0) {
                        end = request.length();
                    }
                }
                ret = request.substring(start, end);
            }
        }
        return ret;
    }

    /** One URL query parameter, decoded, or null when the key is not there. */
    public static String param(String query, String key) {
        if (query == null || key == null) {
            return null;
        }
        String needle = key + "=";
        int idx = query.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        // the key has to start at the beginning or right after a '&', so that a query
        // like "?xpackage_name=a" cannot be mistaken for "package_name=a".
        if (idx != 0 && query.charAt(idx - 1) != '&') {
            return null;
        }
        int valStart = idx + needle.length();
        int amp = query.indexOf('&', valStart);
        String value = amp >= 0 ? query.substring(valStart, amp) : query.substring(valStart);
        try {
            value = URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            // on a malformed %-escape the raw (undecoded) value is returned, as before
        }
        return value;
    }

    /** Query string -> the extras handleCommand() reads. */
    public static void applyQuery(Intent intent, String query) {
        String cmd = param(query, "cmd");
        if (cmd != null) {
            intent.putExtra("cmd", cmd);
        }
        String pkg = param(query, "package_name");
        if (pkg != null) {
            intent.putExtra("package_name", pkg);
        }
        String x = param(query, "target_cellX");
        if (x != null) {
            try {
                intent.putExtra("target_cellX", Integer.parseInt(x));
            } catch (Exception e) {
                // a non-numeric coordinate is ignored, leaving the default (-1)
            }
        }
        String y = param(query, "target_cellY");
        if (y != null) {
            try {
                intent.putExtra("target_cellY", Integer.parseInt(y));
            } catch (Exception e) {
                // ignored
            }
        }
        String screen = param(query, "screen");
        if (screen != null) {
            try {
                intent.putExtra("screen", Integer.parseInt(screen));
            } catch (Exception e) {
                // ignored
            }
        }
    }

    /** Write the whole response: status line, two headers, the body, then close. */
    public static void respond(OutputStream out, int code, String reason,
            String contentType, String body) {
        try {
            byte[] bodyBytes = body.getBytes("UTF-8");
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(code).append(" ").append(reason);
            sb.append("\r\nContent-Type: ").append(contentType);
            sb.append("; charset=utf-8\r\nContent-Length: ").append(bodyBytes.length);
            sb.append("\r\nConnection: close\r\n\r\n");
            out.write(sb.toString().getBytes("UTF-8"));
            out.write(bodyBytes);
            out.flush();
        } catch (Throwable t) {
            Log.e("AdbControl", t.toString());
        }
    }
}
