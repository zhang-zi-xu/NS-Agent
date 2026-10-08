package com.nongxin.service.impl;

import com.nongxin.domain.context.PhenologyResult;
import com.nongxin.service.PhenologyService;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 生育期推算（简化天数窗口模型；输出标注「估算」）。 */
@Service
public class PhenologyServiceImpl implements PhenologyService {

    private record Phase(String name, int dayFrom, int dayTo, String notes) {}

    private static final Map<String, List<Phase>> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put(
                "水稻",
                List.of(
                        new Phase("秧苗期", 0, 25, "播种至移栽，注意育秧温度与秧龄"),
                        new Phase("返青分蘖期", 25, 55, "够苗晒田，控蘖防倒"),
                        new Phase("拔节孕穗期", 55, 85, "病虫害窗口，穗颈瘟预防关键期"),
                        new Phase("抽穗扬花期", 85, 100, "浅水保穗，关注稻飞虱与螟虫"),
                        new Phase("灌浆成熟期", 100, 130, "干湿交替，收割前 7 天断水")));
        TABLES.put(
                "小麦",
                List.of(
                        new Phase("出苗分蘖期", 0, 60, "冬前壮苗为核心"),
                        new Phase("返青拔节期", 60, 120, "看苗追肥，预防倒春寒"),
                        new Phase("孕穗抽穗期", 120, 150, "赤霉病「见花打药」关键窗口"),
                        new Phase("灌浆成熟期", 150, 200, "干热风风险，麦黄水预防")));
        TABLES.put(
                "玉米",
                List.of(
                        new Phase("苗期（出苗-拔节）", 0, 30, "查苗补苗，控旺化控窗口 6-8 叶"),
                        new Phase("拔节孕穗期（小喇叭口）", 30, 50, "大喇叭口期玉米螟防治"),
                        new Phase("抽雄吐丝期", 50, 65, "水肥关键期，防卡脖旱"),
                        new Phase("灌浆成熟期", 65, 100, "防倒伏、防锈病，适期收获")));
    }

    public PhenologyResult getPhenology(String crop, String sowDate) {
        List<Phase> table = TABLES.get(crop);
        if (table == null) return new PhenologyResult(null, 0, "");
        LocalDate sowing;
        try {
            sowing = LocalDate.parse(sowDate);
        } catch (Exception e) {
            return new PhenologyResult(null, 0, "");
        }
        long days = ChronoUnit.DAYS.between(sowing, LocalDate.now());
        if (days < 0) return new PhenologyResult(null, 0, "播期尚未到达，不能据此判断地里已有作物；请确认这是计划播期还是历史记录");
        int d = (int) Math.max(days, 0);
        if ("小麦".equals(crop))
            return new PhenologyResult(
                    null,
                    d,
                    "距档案播期 " + d + " 天；未区分冬春麦、地区、品种及当前茬口，不能仅按天数可靠推算小麦生育期。请以本茬田间观察为准，历史播期不代表当前仍有作物");
        for (Phase p : table) {
            if (d >= p.dayFrom() && d < p.dayTo()) {
                return new PhenologyResult(
                        p.name(), d, p.name() + "（仅按天数粗估，未核实地区、品种或当前茬口；不能作为用药、施肥或作业安排的依据）");
            }
        }
        Phase last = table.get(table.size() - 1);
        return new PhenologyResult(null, d, "播期已超" + last.dayTo() + "天，请确认实际生育期");
    }
}
