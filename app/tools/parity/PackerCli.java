package com.dsh.eye;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** 对拍用：stdin 每行一组 tiers（逗号分隔），输出该组的结果。不进 APK。 */
public final class PackerCli {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            int[] tiers = new int[parts.length];
            for (int i = 0; i < parts.length; i += 1) tiers[i] = Integer.parseInt(parts[i].trim());
            Packer.Result r = Packer.solve(tiers, Packer.DEFAULT_STRETCH, Packer.DEFAULT_BIN_COST);
            StringBuilder sb = new StringBuilder();
            sb.append("CASE ").append(line).append(" bins=").append(r.bins)
              .append(" area=").append(fmt(r.area));
            System.out.println(sb);
            for (Packer.Cell c : r.cells) {
                System.out.println("  " + c.seq + "," + c.t + "," + c.bin + ","
                    + fmt(c.x) + "," + fmt(c.y) + "," + fmt(c.w) + "," + fmt(c.h));
            }
        }
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.6f", v); }
}
