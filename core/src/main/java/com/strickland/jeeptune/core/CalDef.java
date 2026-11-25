package com.strickland.jeeptune.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A calibration definition (which tables, curves and scalars live where in a flash image), read from the def JSON. */
public final class CalDef {
    /** "deg = raw/2" style conversion from the stored number to a value with a unit. */
    public static final class Scale {
        public final String text, unit;
        public final char op;
        public final double k;
        public final boolean signed, parsed;

        private Scale(String text, String unit, char op, double k, boolean signed, boolean parsed) {
            this.text = text;
            this.unit = unit;
            this.op = op;
            this.k = k;
            this.signed = signed;
            this.parsed = parsed;
        }

        public static final Scale RAW = new Scale("raw", "raw", '*', 1, false, true);
        private static final Pattern P = Pattern.compile("^\\s*(.*?)\\s*=\\s*raw\\s*([*/+\\-])\\s*([0-9]*\\.?[0-9]+)(.*)$");

        public static Scale parse(String t) {
            if (t == null || t.trim().isEmpty()) return RAW;
            Matcher m = P.matcher(t);
            boolean signed = t.toLowerCase(java.util.Locale.ROOT).contains("signed");
            if (m.matches()) {
                return new Scale(t, m.group(1), m.group(2).charAt(0), Double.parseDouble(m.group(3)), signed, true);
            }
            // a formula this reader does not know (or just a unit): show the stored number as is
            return new Scale(t, t.contains("=") ? "raw" : t.trim(), '*', 1, signed, false);
        }

        public double apply(double raw) {
            switch (op) {
                case '/': return raw / k;
                case '+': return raw + k;
                case '-': return raw - k;
                default: return raw * k;
            }
        }

        /** Decimals worth showing for one step of the stored number. */
        public int decimals() {
            double step = op == '/' ? 1.0 / k : (op == '*' ? k : 1);
            step = Math.abs(step);
            if (step >= 1 - 1e-9) return 0;
            if (step >= 0.1) return 1;
            if (step >= 0.01) return 2;
            return 3;
        }
    }

    public static final class Axis {
        public final String id, type;
        public final int addr, count;     // count: for u16be[n]; 0 for "x values of a curve"
        public final boolean curveX;
        public final Scale scale;

        Axis(String id, int addr, String type, Scale scale) {
            this.id = id;
            this.addr = addr;
            this.type = type;
            this.scale = scale;
            this.curveX = type.contains("curve2d");
            Matcher m = Pattern.compile("\\[(\\d+)]").matcher(type);
            this.count = m.find() ? Integer.parseInt(m.group(1)) : 0;
        }
    }

    public static final class TableDef {
        public final String id, name, kind, rows, cols, confidence, note;
        public final int addr;
        public final Scale scale, xScale, yScale;

        TableDef(Map<String, Object> m) {
            id = MiniJson.str(m, "id");
            String n = MiniJson.str(m, "name");
            name = n == null ? id : n;
            kind = MiniJson.str(m, "kind");
            rows = MiniJson.str(m, "rows");
            cols = MiniJson.str(m, "cols");
            confidence = MiniJson.str(m, "confidence");
            note = MiniJson.str(m, "note");
            addr = parseAddr(MiniJson.str(m, "addr"));
            scale = Scale.parse(MiniJson.str(m, "scale"));
            xScale = Scale.parse(MiniJson.str(m, "x"));
            yScale = Scale.parse(MiniJson.str(m, "y"));
        }

        public boolean isCurve() { return "curve2d".equals(kind); }

        /** Name without the trailing "?" the def uses for guesses. */
        public String cleanName() { return name.endsWith("?") ? name.substring(0, name.length() - 1).trim() : name; }

        /** True when the def marks this one as unconfirmed ("?" in the name or confidence below high). */
        public boolean uncertain() {
            return name.endsWith("?") || (confidence != null && !"high".equalsIgnoreCase(confidence));
        }
    }

    public static final class ScalarDef {
        public final String id, name, type, stock, confidence;
        public final int addr;
        public final Scale scale;

        ScalarDef(Map<String, Object> m) {
            id = MiniJson.str(m, "id");
            String n = MiniJson.str(m, "name");
            name = n == null ? id : n;
            type = MiniJson.str(m, "type");
            stock = MiniJson.str(m, "stock");
            confidence = MiniJson.str(m, "confidence");
            addr = parseAddr(MiniJson.str(m, "addr"));
            scale = Scale.parse(MiniJson.str(m, "scale"));
        }

        public String cleanName() { return name.endsWith("?") ? name.substring(0, name.length() - 1).trim() : name; }

        public boolean uncertain() {
            return name.endsWith("?") || (confidence != null && !"high".equalsIgnoreCase(confidence));
        }

        public int size() {
            if ("u32be".equals(type)) return 4;
            if ("u16be".equals(type)) return 2;
            return 1;
        }
    }

    public final String part;
    public final int imageSize;
    public final String imageSha256;
    public final Map<String, Axis> axes = new LinkedHashMap<>();
    public final List<TableDef> tables = new ArrayList<>();
    public final List<ScalarDef> scalars = new ArrayList<>();

    private CalDef(Map<String, Object> root) {
        part = MiniJson.str(root, "part") == null ? "" : MiniJson.str(root, "part");
        Object img = root.get("image");
        int size = 0;
        String sha = "";
        if (img != null) {
            Map<String, Object> im = MiniJson.obj(img);
            if (im.get("size") instanceof Double) size = ((Double) im.get("size")).intValue();
            if (im.get("sha256") != null) sha = String.valueOf(im.get("sha256"));
        }
        imageSize = size;
        imageSha256 = sha;
        Object ax = root.get("axes");
        if (ax != null) {
            for (Map.Entry<String, Object> e : MiniJson.obj(ax).entrySet()) {
                Map<String, Object> a = MiniJson.obj(e.getValue());
                String addr = MiniJson.str(a, "addr");
                if (addr == null) continue;   // "temp" has only a scale
                axes.put(e.getKey(), new Axis(e.getKey(), parseAddr(addr), String.valueOf(a.get("type")), Scale.parse(MiniJson.str(a, "scale"))));
            }
        }
        if (root.get("tables") != null) for (Object o : MiniJson.arr(root.get("tables"))) tables.add(new TableDef(MiniJson.obj(o)));
        if (root.get("scalars") != null) for (Object o : MiniJson.arr(root.get("scalars"))) scalars.add(new ScalarDef(MiniJson.obj(o)));
    }

    public static CalDef parse(String json) { return new CalDef(MiniJson.obj(MiniJson.parse(json))); }

    public TableDef table(String id) {
        for (TableDef t : tables) if (t.id.equals(id)) return t;
        return null;
    }

    static int parseAddr(String a) {
        if (a == null) throw new IllegalArgumentException("address missing");
        String t = a.trim();
        return t.startsWith("0x") || t.startsWith("0X") ? Integer.parseInt(t.substring(2), 16) : Integer.parseInt(t);
    }
}
