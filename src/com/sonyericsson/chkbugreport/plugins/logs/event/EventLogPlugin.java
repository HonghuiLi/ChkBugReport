/*
 * Copyright (C) 2011 Sony Ericsson Mobile Communications AB
 *
 * This file is part of ChkBugReport.
 *
 * ChkBugReport is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * ChkBugReport is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ChkBugReport.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.sonyericsson.chkbugreport.plugins.logs.event;

import com.sonyericsson.chkbugreport.Bug;
import com.sonyericsson.chkbugreport.BugReport;
import com.sonyericsson.chkbugreport.Chapter;
import com.sonyericsson.chkbugreport.Report;
import com.sonyericsson.chkbugreport.Section;
import com.sonyericsson.chkbugreport.Util;
import com.sonyericsson.chkbugreport.plugins.logs.LogLine;
import com.sonyericsson.chkbugreport.plugins.logs.LogPlugin;

import java.util.HashMap;
import java.util.Vector;

public class EventLogPlugin extends LogPlugin {

    private static final int[] ALT_LEVELS = { 250, 1000 };

    private static final int TAG_DVM_LOCK_SAMPLE = 20003;
    private static final int TAG_DB_SAMPLE = 52000;
    private static final int TAG_CONTENT_QUERY_SAMPLE = 52002;
    private static final int TAG_CONTENT_UPDATE_SAMPLE = 52003;
    private static final int TAG_BINDER_SAMPLE = 52004;
    private static final int TAG_NETSTATS_MOBILE_SAMPLE = 51100;
    private static final int TAG_NETSTATS_WIFI_SAMPLE = 51101;

    private Vector<ALTStat> mALT = new Vector<ALTStat>();
    /* db_sample stats */
    private HashMap<String, DBStat> mDBStats = new HashMap<String, DBStat>();
    /* content_query_sample stats */
    private HashMap<String, DBStat> mCQStats = new HashMap<String, DBStat>();
    /* content_update_sample stats */
    private HashMap<String, DBStat> mCUStats = new HashMap<String, DBStat>();
    /* content_query_sample + content_update_stats */
    private HashMap<String, DBStat> mCTStats = new HashMap<String, DBStat>();
    /* The collection of *_sample data */
    private SampleDatas mSDs;

    private ActivityManagerTrace mAM;

    public EventLogPlugin() {
        super("Event", "event", Section.EVENT_LOG);
    }

    @Override
    public int getPrio() {
        return 32;
    }

    @Override
    public void load(Report rep) {
        BugReport br = (BugReport) rep;
        mALT.clear();
        mDBStats.clear();
        mCQStats.clear();
        mCUStats.clear();
        mCTStats.clear();
        mSDs = new SampleDatas();
        mAM = new ActivityManagerTrace(this, br);
        super.load(br);
        mAM.finishLoad();
    }

    @Override
    protected void generateExtra(BugReport rep, Chapter ch) {
        BugReport br = (BugReport)rep;

        // Collect *_sample data in statistics
        collectSampleStats();

        // Finish sub-chapters
        finishActivityLaunchTime(br, ch);
        finishDBStats(br, ch);
        new SampleDatasGenerator(this, mSDs).run(br, ch);
        new ActivityManagerGraphGenerator(this, mAM).run(br, ch);
        new ActivityManagerStatsGenerator(this, mAM).run(br, ch);
        new ActivityManagerProcStatsGenerator(this, mAM).run(br, ch);
    }

    @Override
    protected void analyze(LogLine sl, int i, BugReport br, Section s) {
        String eventType = Util.strip(sl.tag);
        if (sl.fmt == LogLine.FMT_CRASH) {
            // Crash is too smart, it also parses the logs, so we need different method for analyzes
            if (sl.tagId == TAG_DVM_LOCK_SAMPLE) {
                addDvmLockSampleDataC(br, "dvm_lock_sample", sl);
            } else if (sl.tagId == TAG_DB_SAMPLE) {
                addGenericSampleDataC(br, "db_sample", sl);
            } else if (sl.tagId == TAG_CONTENT_QUERY_SAMPLE) {
                addGenericSampleDataC(br, "content_query_sample", sl);
            } else if (sl.tagId == TAG_CONTENT_UPDATE_SAMPLE) {
                addGenericSampleDataC(br, "content_update_sample", sl);
            } else if (sl.tagId == TAG_BINDER_SAMPLE) {
                addGenericSampleDataC(br, "binder_sample", sl);
            } else if (sl.tagId == TAG_NETSTATS_MOBILE_SAMPLE) {
                // Ignore (TODO)
            } else if (sl.tagId == TAG_NETSTATS_WIFI_SAMPLE) {
                // Ignore (TODO)
            }
        } else {
            if ("netstats_mobile_sample".equals(eventType)) {
                // Ignore (TODO)
                return;
            }
            if ("netstats_wifi_sample".equals(eventType)) {
                // Ignore (TODO)
                return;
            }
            if (eventType.endsWith("_sample")) {
                addSampleData(br, eventType, sl);
                // Fall through: some of the sample data is handled more then once
            }
            if ("am_anr".equals(eventType)) {
                analyzeCrashOrANR(sl, i, br, "anr");
                // Fall through: am_ logs are processed again
            }
            if ("am_crash".equals(eventType)) {
                analyzeCrashOrANR(sl, i, br, "crash");
                // Fall through: am_ logs are processed again
            }
            if ("activity_launch_time".equals(eventType)) {
                addActivityLaunchTimeData(sl.fields, i);
                addActivityLaunchMarker(sl);
            } else if (eventType.startsWith("am_")) {
                mAM.addAMData(eventType, br, sl, i);
            } else if ("dvm_gc_info".equals(eventType)) {
                addDvmGCInfoData(sl);
            } else if ("configuration_changed".equals(eventType)) {
                handleConfigChanged(sl);
            }
        }
    }

    private void addActivityLaunchMarker(LogLine sl) {
        if (sl.fields.length == 4) {
            addActivityLaunchMarker(sl, sl.fields[1]);
        }
    }

    private void handleConfigChanged(LogLine sl) {
        // Collect the info for GC graph
        ConfigChange cc = new ConfigChange(sl.ts);
        addConfigChange(cc);

        // Create a marker in the log
        try {
            int changed = Integer.parseInt(sl.msg);
            StringBuffer sb = new StringBuffer();
            if (0 != (changed & 0x0001)) {
                sb.append("MCC<br/>");
            }
            if (0 != (changed & 0x0002)) {
                sb.append("MNC<br/>");
            }
            if (0 != (changed & 0x0004)) {
                sb.append("Locale<br/>");
            }
            if (0 != (changed & 0x0008)) {
                sb.append("Touch<br/>");
            }
            if (0 != (changed & 0x0010)) {
                sb.append("Keyb.<br/>");
            }
            if (0 != (changed & 0x0020)) {
                sb.append("HW Kb<br/>");
            }
            if (0 != (changed & 0x0040)) {
                sb.append("Navig.<br/>");
            }
            if (0 != (changed & 0x0080)) {
                sb.append("Orient.<br/>");
            }
            if (0 != (changed & 0x0100)) {
                sb.append("Layout<br/>");
            }
            if (0 != (changed & 0x0200)) {
                sb.append("UI Mode<br/>");
            }
            if (0 != (changed & 0x0400)) {
                sb.append("Theme<br/>");
            }
            if (0 != (changed & 0x40000000)) {
                sb.append("Font<br/>");
            }
            sl.addMarker("log-float-icon", "style=\"font-size: 75%\"", sb.toString(), null);
        } catch (NumberFormatException nfe) { /* NOP */ }
    }

    private void analyzeCrashOrANR(LogLine sl, int i, BugReport br, String type) {
        // Put a marker box
        String cType = type.toUpperCase();
        String anchor = getId() + "elog_" + type + "_" + i;
        sl.addMarker("log-float-err", null, "<a name=\"" + anchor + "\">" + cType + "</a>", cType);

        // Create a bug and store the relevant log lines
        String msg = null;
        int pid = -1;
        if (sl.fields.length < 4) {
            // Strange... let's use the log message completely
            msg = cType + ": " + sl.msg;
        } else {
            msg = cType + " in '" + sl.fields[1] + "' (" + sl.fields[0] + ")";
            try {
                pid = Integer.parseInt(sl.fields[0]);
            } catch (NumberFormatException nfe) { /* NOP */ }
        }
        int prio = type.equals("anr") ? Bug.PRIO_ANR_EVENT_LOG : Bug.PRIO_JAVA_CRASH_EVENT_LOG;
        Bug bug = new Bug(prio, sl.ts, msg);
        bug.setAttr("firstLine", i);
        bug.addLine("<div><a href=\"" + br.createLinkTo(getChapter(), anchor) + "\">(link to event log)</a></div>");
        if (pid != -1) {
            bug.addLine("<div><a href=\"" + br.createLinkToProcessRecord(pid) + "\">(link to process record)</a></div>");
        }
        bug.addLine("<div class=\"log\">");
        bug.addLine(sl.html);
        bug.addLine("</div>");
        bug.addLine("<pre>");
        if (sl.fields.length >= 4) {
            bug.setAttr("pid", sl.fields[0]);
            bug.setAttr("package", sl.fields[1]);
            bug.setAttr("reason", sl.fields[3]);

            // Print some additional info
            int flags = -1;
            try {
                flags = Integer.parseInt(sl.fields[2]);
            } catch (NumberFormatException nfe) { /* NOP */ }
            bug.addLine("PID:            " + sl.fields[0]);
            bug.addLine("Package:        " + sl.fields[1]);
            bug.addLine("Reason:         " + sl.fields[3]);
            bug.addLine("Flags:          0x" + Integer.toHexString(flags) + ":");
            bug.addLine("  - SYSTEM:                    " + (0 != (flags & (1 << 0))));
            bug.addLine("  - DEBUGGABLE:                " + (0 != (flags & (1 << 1))));
            bug.addLine("  - HAS_CODE:                  " + (0 != (flags & (1 << 2))));
            bug.addLine("  - PERSISTENT:                " + (0 != (flags & (1 << 3))));
            bug.addLine("  - FACTORY TEST:              " + (0 != (flags & (1 << 4))));
            bug.addLine("  - ALLOW TASK REPARENTING:    " + (0 != (flags & (1 << 5))));
            bug.addLine("  - ALLOW CLEAR USERDATA:      " + (0 != (flags & (1 << 6))));
            bug.addLine("  - UPDATED SYSTEM APP:        " + (0 != (flags & (1 << 7))));
            bug.addLine("  - TEST ONLY:                 " + (0 != (flags & (1 << 8))));
            bug.addLine("  - SUPPORTS SMALL SCREENS:    " + (0 != (flags & (1 << 9))));
            bug.addLine("  - SUPPORTS NORMAL SCREENS:   " + (0 != (flags & (1 << 10))));
            bug.addLine("  - SUPPORTS LARGE SCREENS:    " + (0 != (flags & (1 << 11))));
            bug.addLine("  - SUPPORTS XLARGE SCREENS:   " + (0 != (flags & (1 << 19))));
            bug.addLine("  - RESIZEABLE FOR SCREENS:    " + (0 != (flags & (1 << 12))));
            bug.addLine("  - SUPPORTS SCREEN DENSITIES: " + (0 != (flags & (1 << 13))));
            bug.addLine("  - VM SAFE MODE:              " + (0 != (flags & (1 << 14))));
            bug.addLine("  - ALLOW BACKUP:              " + (0 != (flags & (1 << 15))));
            bug.addLine("  - KILL AFTER RESTORE:        " + (0 != (flags & (1 << 16))));
            bug.addLine("  - RESTORE ANY VERSION:       " + (0 != (flags & (1 << 17))));
            bug.addLine("  - EXTERNAL STORAGE:          " + (0 != (flags & (1 << 18))));
            bug.addLine("  - CANT SAVE STATE:           " + (0 != (flags & (1 << 27))));
            bug.addLine("  - FORWARD LOCK:              " + (0 != (flags & (1 << 29))));
            bug.addLine("  - NEVER ENCRYPT:             " + (0 != (flags & (1 << 30))));
        }
        bug.addLine("</pre>");
        br.addBug(bug);
    }

    private void addDvmGCInfoData(LogLine sl) {
        if (sl.fields.length != 4) return;
        try {
//            long l0 = Long.parseLong(sl.fields[0]);
            long l1 = Long.parseLong(sl.fields[1]);
//            long l2 = Long.parseLong(sl.fields[2]);
            long l3 = Long.parseLong(sl.fields[3]);

//            int gcTime = unFloat12(Util.bits(l0, 23, 12));
//            int bytesFreed = unFloat12(Util.bits(l0, 11, 0));

//            int objsFreed = unFloat12(Util.bits(l1, 59, 48));
            int agrActSize = unFloat12(Util.bits(l1, 47, 36));
//            int agrAwdSize = unFloat12(Util.bits(l1, 35, 24));
//            int agrObjsAlloc = unFloat12(Util.bits(l1, 23, 12));
            int agrBytesAlloc = unFloat12(Util.bits(l1, 11, 0));

//            int softLimit = unFloat12(Util.bits(l2, 59, 48));
//            int zygActSize = unFloat12(Util.bits(l2, 47, 36));
//            int zygAwdSize = unFloat12(Util.bits(l2, 35, 24));
//            int zygObjsAlloc = unFloat12(Util.bits(l2, 23, 12));
//            int zygBytesAlloc = unFloat12(Util.bits(l2, 11, 0));

            // int footprint = unFloat12(Util.bits(l3, 47, 36)); // Disabled
            // int mallinfo = unFloat12(Util.bits(l3, 35, 24)); // Disabled
            int extLim = unFloat12(Util.bits(l3, 23, 12));
            int extAlloc = unFloat12(Util.bits(l3, 11, 0));

            GCRecord gc = new GCRecord(sl.ts, sl.pid, agrBytesAlloc / 1024, agrActSize / 1024, extAlloc / 1024, extLim / 1024);
            addGCRecord(sl.pid, gc);
        } catch (NumberFormatException nfe) {
            // Just ignore it, it's not the end of the world
        }
    }

    private int unFloat12(long value) {
        return (int)((value & 0x1ff) << ((value >> 9) * 4));
    }

    private void addSampleData(Report br, String eventType, LogLine sl) {
        int fieldCount = sl.fields.length;
        if (fieldCount < 4) return; // cannot handle these
        try {
            int duration = 0, perc = 0;
            String name = null;
            if (eventType.equals("dvm_lock_sample")) {
                // This is a bit different
                duration = Integer.parseInt(sl.fields[fieldCount-2]);
                perc = Integer.parseInt(sl.fields[fieldCount-1]);
                name = sl.fields[0];
            } else {
                duration = Integer.parseInt(sl.fields[fieldCount-3]);
                perc = Integer.parseInt(sl.fields[fieldCount-1]);
                name = sl.fields[0];
                name = fixSampleDataName(name);
            }
            SampleData sd = new SampleData(sl.ts, sl.pid, name, duration, perc);
            addSampleData(eventType, sd);
        } catch (NumberFormatException e) {
            br.printErr(4, TAG + "addSampleData(eventType=" + eventType + "):" + e);
        }
    }

    private String fixSampleDataName(String name) {
        if (name.startsWith("content://")) {
            name = name.substring(10);

            // Need to optimize this, otherwise there will be too many values
            int idx = name.indexOf('/', 10);
            if (idx > 0) {
                name = name.substring(0, idx);
            }
        }
        return name;
    }

    private void addSampleData(String eventType, SampleData sd) {
        mSDs.addData(eventType, sd);
    }

    private void addDvmLockSampleDataC(Report br, String eventType, LogLine sl) {
        int fieldCount = sl.fields.length;
        if (fieldCount < 4) return; // cannot handle these
        try {
            int duration = extractIntValueFromCrashLogField(sl.fields[fieldCount-2]);
            int perc = extractIntValueFromCrashLogField(sl.fields[fieldCount-1]);
            String name = extractValueFromCrashLogField(sl.fields[0]);
            SampleData sd = new SampleData(sl.ts, sl.pid, name, duration, perc);
            addSampleData(eventType, sd);
        } catch (NumberFormatException e) {
            br.printErr(4, TAG + "addSampleData(eventType=" + eventType + "):" + e);
        }
    }

    private void addGenericSampleDataC(Report br, String eventType, LogLine sl) {
        int fieldCount = sl.fields.length;
        if (fieldCount < 4) return; // cannot handle these
        try {
            int duration = extractIntValueFromCrashLogField(sl.fields[fieldCount-3]);
            int perc = extractIntValueFromCrashLogField(sl.fields[fieldCount-1]);
            String name = extractValueFromCrashLogField(sl.fields[0]);
            name = fixSampleDataName(name);
            SampleData sd = new SampleData(sl.ts, sl.pid, name, duration, perc);
            addSampleData(eventType, sd);
        } catch (NumberFormatException e) {
            br.printErr(4, TAG + "addSampleData(eventType=" + eventType + "):" + e);
        }
    }

    private String extractValueFromCrashLogField(String s) {
        if (s.startsWith("{") && s.endsWith("}")) {
            s = s.substring(1, s.length() - 1);
        }
        int idx = s.indexOf('=');
        if (idx > 0) {
            if (canBeKeyword(s.substring(0, idx))) {
                s = s.substring(idx + 1);
            }
        }
        return s;
    }

    private int extractIntValueFromCrashLogField(String s) {
        s = extractValueFromCrashLogField(s).toLowerCase();
        int idx = s.indexOf(' ');
        if (idx > 0) {
            s = s.substring(0, idx);
        }
        if (s.startsWith("0x")) {
            return Integer.parseInt(s.substring(2), 16);
        } else {
            return Integer.parseInt(s);
        }
    }

    private boolean canBeKeyword(String s) {
        int l = s.length();
        for (int i = 0; i < l; i++) {
            char c = s.charAt(i);
            boolean ok = false;
            if (c >= 'a' && c <= 'z') {
                ok = true;
            } else if (c >= 'A' && c <= 'Z') {
                ok = true;
            } else if (c >= '0' && c <= '9') {
                ok = true;
            } else if (c == '_') {
                ok = true;
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private void addActivityLaunchTimeData(String[] fields, int line) {
        String activity = fields[1];
        int time = Integer.parseInt(fields[2]);
        int total = Integer.parseInt(fields[3]);
        ALTStat alt = new ALTStat();
        alt.activity = activity;
        alt.time = time;
        alt.total = total;
        alt.line = line;
        mALT.add(alt);
    }

    private void finishActivityLaunchTime(BugReport br, Chapter ch) {
        if (mALT.size() == 0) return;

        Chapter chALT = new Chapter(br, "Stats - Activity launch time");
        ch.addChapter(chALT);
        chALT.addLine("<div class=\"hint\">(Hint: click on the headers to sort the data. Shift+click to sort on multiple columns.)</div>");
        chALT.addLine("<table class=\"mll tablesorter\">");
        chALT.addLine("<thead>");
        chALT.addLine("<tr>");
        chALT.addLine("<th>Activity</td>");
        chALT.addLine("<th>Time(ms)</td>");
        chALT.addLine("<th>Total(ms)</td>");
        chALT.addLine("</tr>");
        chALT.addLine("</thead>");
        chALT.addLine("<tbody>");

        boolean odd = false;
        for (ALTStat alt : mALT) {
            odd = !odd;
            int levelTime = findLevel(alt.time, ALT_LEVELS);
            int levelTotal = findLevel(alt.total, ALT_LEVELS);
            chALT.addLine("<tr class=\"mll-" + (odd ? "odd" : "even") + "\">");
            chALT.addLine("<td><a href=\"#" + getAnchorToLine(alt.line) + "\">" + alt.activity + "</a></td>");
            chALT.addLine("<td class=\"mll-level" + levelTime + "\">" + alt.time + "</td>");
            chALT.addLine("<td class=\"mll-level" + levelTotal + "\">" + alt.total + "</td>");
            chALT.addLine("</tr>");
        }

        chALT.addLine("</tbody>");
        chALT.addLine("<table>");
    }

    private void collectSampleStats() {
        collectSampleStats("db_sample", mDBStats);
        collectSampleStats("content_query_sample", mCQStats);
        collectSampleStats("content_query_sample", mCTStats);
        collectSampleStats("content_update_sample", mCUStats);
        collectSampleStats("content_update_sample", mCTStats);
    }

    private void collectSampleStats(String eventType, HashMap<String, DBStat> stats) {
        Vector<SampleData> datas = mSDs.getSamplesByType(eventType);
        if (datas == null) return;
        for (SampleData sd : datas) {
            addDBData(sd.pid, sd.name, sd.duration, stats);
        }
    }

    private void addDBData(int pid, String db, int time, HashMap<String, DBStat> stats) {
        DBStat stat = stats.get(db);
        if (stat == null) {
            stat = new DBStat();
            stat.db = db;
            stats.put(db, stat);
        }
        stat.count++;
        stat.totalTime += time;
        if (time > stat.maxTime) {
            stat.maxTime = time;
        }
        if (!stat.pids.contains(pid)) {
            stat.pids.add(pid);
        }
    }

    private void finishDBStats(BugReport br, Chapter ch) {
        writeDBStats(mDBStats, "Stats - Direct DB access", br, ch);
        writeDBStats(mCQStats, "Stats - Content query", br, ch);
        writeDBStats(mCUStats, "Stats - Content update", br, ch);
        writeDBStats(mCTStats, "Stats - Content query + update", br, ch);
    }

    private void writeDBStats(HashMap<String,DBStat> stats, String title, BugReport br, Chapter ch) {
        if (stats.size() == 0) return;

        Chapter chDB = new Chapter(br, title);
        ch.addChapter(chDB);
        chDB.addLine("<div class=\"hint\">(Hint: click on the headers to sort the data. Shift+click to sort on multiple columns.)</div>");
        chDB.addLine("<table class=\"mll tablesorter\">");
        chDB.addLine("<thead>");
        chDB.addLine("<tr>");
        chDB.addLine("<th>Database</td>");
        chDB.addLine("<th>Total(ms)</td>");
        chDB.addLine("<th>Max(ms)</td>");
        chDB.addLine("<th>Avg(ms)</td>");
        chDB.addLine("<th>Samples</td>");
        chDB.addLine("<th>Pids</td>");
        chDB.addLine("</tr>");
        chDB.addLine("</thead>");
        chDB.addLine("<tbody>");

        boolean odd = false;
        for (DBStat db : stats.values()) {
            odd = !odd;
            chDB.addLine("<tr class=\"mll-" + (odd ? "odd" : "even") + "\">");
            chDB.addLine("<td>" + db.db + "</td>");
            chDB.addLine("<td>" + db.totalTime + "</td>");
            chDB.addLine("<td>" + db.maxTime + "</td>");
            chDB.addLine("<td>" + (db.totalTime / db.count) + "</td>");
            chDB.addLine("<td>" + db.count + "</td>");
            chDB.addLine("<td>");
            for (int pid : db.pids) {
                chDB.addLine(Util.convertPidToLink(br, pid));
            }
            chDB.addLine("</td>");
            chDB.addLine("</tr>");
        }

        chDB.addLine("</tbody>");
        chDB.addLine("<table>");
    }

    private int findLevel(int value, int[] levels) {
        int cnt = levels.length;
        for (int i = 0; i < cnt; i++) {
            if (value <= levels[i]) {
                return i;
            }
        }
        return cnt;
    }

}
