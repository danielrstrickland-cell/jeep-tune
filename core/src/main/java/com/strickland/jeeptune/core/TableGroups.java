package com.strickland.jeeptune.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The chips on the viewer: each is one table or a small set of curves that belong together. */
public final class TableGroups {
    public static final class Group {
        public final String label;
        public final List<CalDef.TableDef> members = new ArrayList<>();
        Group(String label) { this.label = label; }
    }

    private static final String[][] LAYOUT = {
            {"Spark A", "sparkA"}, {"Spark B", "sparkB"}, {"After-start spark", "sparkC"},
            {"Fuel A", "fuelA"}, {"Fuel B", "fuelB"},
            {"Charge temp retard", "iatRetard"}, {"Coolant retard", "ectRetard"},
            {"Idle spark curves", "idleSparkPN", "idleSparkDrive", "idleSparkLow", "idleSparkHigh"},
            {"Idle rpm", "idleRpmA", "idleRpmB"},
            {"3-4 shift", "shift34Up", "shift34Down"}};

    private TableGroups() { }

    /** Groups in chip order; tables the def has but the layout does not name get their own chip at the end. */
    public static List<Group> build(CalDef def) {
        List<Group> out = new ArrayList<>();
        List<String> used = new ArrayList<>();
        for (String[] l : LAYOUT) {
            Group g = new Group(l[0]);
            for (String id : Arrays.asList(l).subList(1, l.length)) {
                CalDef.TableDef t = def.table(id);
                if (t != null) { g.members.add(t); used.add(id); }
            }
            if (!g.members.isEmpty()) out.add(g);
        }
        for (CalDef.TableDef t : def.tables) {
            if (used.contains(t.id)) continue;
            Group g = new Group(t.cleanName());
            g.members.add(t);
            out.add(g);
        }
        return out;
    }
}
