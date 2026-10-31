package kr.pokeevents;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The values of config/poke-events.json that /pokeevent set changes in game: name -> how to show, parse and store it.
 * Chances are typed and shown in percent.
 */
public final class Settings {
    /** One setting: label for the list, current value as text, and a setter that returns a problem or null. */
    record Setting(String label, Function<EventSchedule, String> get, BiConsumer<EventSchedule, Object> set, Kind kind,
                   double min, double max) {
    }

    enum Kind { BOOL, INT, NUMBER, PERCENT }

    static final Map<String, Setting> ALL = new LinkedHashMap<>();

    static {
        add("random.enabled", "무작위 이벤트 켜기", Kind.BOOL, 0, 0,
            s -> s.random.enabled, (s, v) -> s.random.enabled = (Boolean) v);
        add("random.every", "무작위 이벤트 주기(분)", Kind.INT, 1, 1440,
            s -> s.random.everyMinutes, (s, v) -> s.random.everyMinutes = ((Number) v).intValue());
        add("random.minutes", "무작위 이벤트 진행 시간(분)", Kind.INT, 1, 1440,
            s -> s.random.minutes, (s, v) -> s.random.minutes = ((Number) v).intValue());
        add("random.shiny", "이로치 확률 배율", Kind.NUMBER, 1, 100,
            s -> s.random.shinyMultiplier, (s, v) -> s.random.shinyMultiplier = ((Number) v).floatValue());
        add("random.outbreak", "대량발생 배율", Kind.NUMBER, 1, 100,
            s -> s.random.outbreakMultiplier, (s, v) -> s.random.outbreakMultiplier = ((Number) v).floatValue());
        add("legend.enabled", "전설 출현 켜기", Kind.BOOL, 0, 0,
            s -> s.legends.enabled, (s, v) -> s.legends.enabled = (Boolean) v);
        add("legend.every", "전설 출현 굴리는 주기(분)", Kind.INT, 1, 1440,
            s -> s.legends.everyMinutes, (s, v) -> s.legends.everyMinutes = ((Number) v).intValue());
        add("legend.sub", "준전설 확률(%)", Kind.PERCENT, 0, 100,
            s -> s.legends.subChance, (s, v) -> s.legends.subChance = ((Number) v).doubleValue());
        add("legend.box", "초전설·환상 확률(%)", Kind.PERCENT, 0, 100,
            s -> s.legends.boxChance, (s, v) -> s.legends.boxChance = ((Number) v).doubleValue());
        add("legend.mythical", "환상 포켓몬을 초전설 쪽에 포함", Kind.BOOL, 0, 0,
            s -> s.legends.mythicalAsBox, (s, v) -> s.legends.mythicalAsBox = (Boolean) v);
        add("legend.level", "전설 레벨", Kind.INT, 1, 100,
            s -> s.legends.level, (s, v) -> s.legends.level = ((Number) v).intValue());
        add("legend.levelcap", "RCT 레벨 제한에 맞추기", Kind.BOOL, 0, 0,
            s -> s.legends.capToLevelCap, (s, v) -> s.legends.capToLevelCap = (Boolean) v);
        add("legend.despawn", "전설이 사라지는 시간(분)", Kind.INT, 1, 1440,
            s -> s.legends.despawnMinutes, (s, v) -> s.legends.despawnMinutes = ((Number) v).intValue());
        add("legend.min", "플레이어와 최소 거리(블록)", Kind.INT, 1, 64,
            s -> s.legends.minDistance, (s, v) -> s.legends.minDistance = ((Number) v).intValue());
        add("legend.max", "플레이어와 최대 거리(블록)", Kind.INT, 1, 96,
            s -> s.legends.maxDistance, (s, v) -> s.legends.maxDistance = ((Number) v).intValue());
    }

    private Settings() {
    }

    private static void add(String name, String label, Kind kind, double min, double max,
                            Function<EventSchedule, Object> get, BiConsumer<EventSchedule, Object> set) {
        ALL.put(name, new Setting(label, s -> show(kind, get.apply(s)), set, kind, min, max));
    }

    private static String show(Kind kind, Object value) {
        if (kind == Kind.PERCENT) {
            return number(((Number) value).doubleValue() * 100) + "%";
        }

        if (kind == Kind.BOOL) {
            return (Boolean) value ? "켜짐" : "꺼짐";
        }

        return number(((Number) value).doubleValue());
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** Sets a value from its typed text. Returns a problem, or null when it was set. */
    static String set(EventSchedule schedule, String name, String text) {
        Setting setting = ALL.get(name);

        if (setting == null) {
            return "알 수 없는 설정입니다: " + name;
        }

        String value = text.trim().toLowerCase(Locale.ROOT).replace("%", "");

        if (setting.kind() == Kind.BOOL) {
            Boolean on = switch (value) {
                case "true", "on", "켜기", "켬", "1" -> true;
                case "false", "off", "끄기", "끔", "0" -> false;
                default -> null;
            };

            if (on == null) {
                return "on 또는 off 로 입력하세요: " + text;
            }

            setting.set().accept(schedule, on);
            return null;
        }

        double number;

        try {
            number = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return "숫자를 입력하세요: " + text;
        }

        if (number < setting.min() || number > setting.max() || setting.kind() == Kind.INT && number != Math.floor(number)) {
            return setting.label() + " 은(는) " + number(setting.min()) + " ~ " + number(setting.max())
                + (setting.kind() == Kind.INT ? " 사이의 정수" : " 사이") + "여야 합니다.";
        }

        setting.set().accept(schedule, setting.kind() == Kind.PERCENT ? number / 100 : number);
        return null;
    }

    /** Every setting with its value; clicking a line puts the command to change it in the chat box. */
    static List<Text> lines(EventSchedule schedule) {
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal("===== 이벤트 설정 (클릭하면 바꾸는 명령어가 입력됩니다) =====").formatted(Formatting.GOLD));

        for (Map.Entry<String, Setting> entry : ALL.entrySet()) {
            String command = "/pokeevent set " + entry.getKey() + " ";
            MutableText line = Text.literal(" " + entry.getValue().label() + ": ").formatted(Formatting.GRAY)
                .append(Text.literal(entry.getValue().get().apply(schedule)).formatted(Formatting.YELLOW))
                .append(Text.literal("  (" + entry.getKey() + ")").formatted(Formatting.DARK_GRAY));
            lines.add(line.styled(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(command + "<값>")))));
        }

        lines.add(Text.literal(" 대량발생 후보: " + (schedule.random.species.isEmpty()
                ? "전설·환상 제외 전체 (" + PokeEvents.allOutbreakSpecies().size() + "종)" : String.join(", ", schedule.random.species)))
            .formatted(Formatting.GRAY)
            .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/pokeevent species add "))));
        lines.add(Text.literal(" 전설 제외 목록: " + (schedule.legends.exclude.isEmpty() ? "없음" : String.join(", ", schedule.legends.exclude)))
            .formatted(Formatting.GRAY)
            .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/pokeevent exclude add "))));
        lines.add(Text.literal(" 목록 바꾸기: /pokeevent species add|remove <포켓몬>, /pokeevent exclude add|remove <포켓몬>")
            .formatted(Formatting.DARK_GRAY));
        return lines;
    }
}
