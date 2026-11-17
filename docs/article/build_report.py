#!/usr/bin/env python3
"""Builds the report artifacts for the virtual threads experiment.

Usage:
    python docs/article/build_report.py [vt-on.csv] [vt-off.csv] [output-dir]

The script only uses the Python standard library. It reads two CSV files produced by
ThreadUsageRecorder, builds a combined ON/OFF chart with the load profile, renders the UML
activity diagram of the experiment plan and the request-processing sequence diagram.
"""

from __future__ import annotations

import csv
import math
import os
import sys
from dataclasses import dataclass


@dataclass
class Sample:
    ms: float
    platform: int
    mounted: int
    queued: int
    carrier: int
    active: int


def read_samples(path: str) -> list[Sample]:
    with open(path, newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        return [
            Sample(
                float(row["elapsed_ms"]),
                int(row["platform_threads"]),
                int(row["mounted_virtual_threads"]),
                int(row["queued_virtual_threads"]),
                int(row["carrier_pool_size"]),
                int(row["active_requests"]),
            )
            for row in reader
        ]


def load_segments(samples: list[Sample]) -> list[tuple[float, float]]:
    """Contiguous intervals where at least one chat request is in flight."""
    segments: list[tuple[float, float]] = []
    start: float | None = None
    previous = samples[0].ms
    for sample in samples:
        if sample.active > 0 and start is None:
            start = sample.ms
        if sample.active == 0 and start is not None:
            segments.append((start, previous))
            start = None
        previous = sample.ms
    if start is not None:
        segments.append((start, samples[-1].ms))
    return segments


def last_measurements(samples: list[Sample]) -> list[tuple[float, float]]:
    segments = load_segments(samples)
    return segments[-3:] if len(segments) >= 3 else segments


def fmt(value: float) -> str:
    return f"{value:.1f}"


def esc(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def text(x: float, y: float, value: str, size: int = 12, fill: str = "#333",
         anchor: str = "start", weight: str = "normal", extra: str = "") -> str:
    return (
        f'<text x="{fmt(x)}" y="{fmt(y)}" font-family="Segoe UI, sans-serif" '
        f'font-size="{size}" fill="{fill}" text-anchor="{anchor}" '
        f'font-weight="{weight}" {extra}>{esc(value)}</text>\n'
    )


def rect(x: float, y: float, w: float, h: float, fill: str = "none", stroke: str = "none",
         width: float = 1, opacity: float = 1.0, rx: float = 0) -> str:
    return (
        f'<rect x="{fmt(x)}" y="{fmt(y)}" width="{fmt(w)}" height="{fmt(h)}" '
        f'fill="{fill}" fill-opacity="{opacity}" stroke="{stroke}" stroke-width="{fmt(width)}" '
        f'rx="{fmt(rx)}"/>\n'
    )


def line(x1: float, y1: float, x2: float, y2: float, stroke: str = "#888",
         width: float = 1.0, dash: str | None = None) -> str:
    dash_attr = f' stroke-dasharray="{dash}"' if dash else ""
    return (
        f'<line x1="{fmt(x1)}" y1="{fmt(y1)}" x2="{fmt(x2)}" y2="{fmt(y2)}" '
        f'stroke="{stroke}" stroke-width="{fmt(width)}"{dash_attr}/>\n'
    )


def arrow(x1: float, y1: float, x2: float, y2: float, stroke: str = "#555",
          width: float = 1.6, dash: str | None = None) -> str:
    angle = math.atan2(y2 - y1, x2 - x1)
    size = 9
    left = (x2 - size * math.cos(angle - math.pi / 6), y2 - size * math.sin(angle - math.pi / 6))
    right = (x2 - size * math.cos(angle + math.pi / 6), y2 - size * math.sin(angle + math.pi / 6))
    return (
        line(x1, y1, x2, y2, stroke, width, dash)
        + f'<polygon points="{fmt(x2)},{fmt(y2)} {fmt(left[0])},{fmt(left[1])} '
          f'{fmt(right[0])},{fmt(right[1])}" fill="{stroke}"/>\n'
    )


def polyline(points: list[tuple[float, float]], stroke: str, width: float = 2.0,
             dash: str | None = None) -> str:
    dash_attr = f' stroke-dasharray="{dash}"' if dash else ""
    data = " ".join(f"{fmt(x)},{fmt(y)}" for x, y in points)
    return (
        f'<polyline fill="none" stroke="{stroke}" stroke-width="{fmt(width)}" '
        f'points="{data}"{dash_attr}/>\n'
    )


def nice_max(value: int, step: int) -> int:
    return max(step, math.ceil(value / step) * step)


def build_combined(on_path: str, off_path: str, output: str) -> dict[str, float]:
    on = read_samples(on_path)
    off = read_samples(off_path)

    on_measurements = last_measurements(on)
    off_measurements = last_measurements(off)
    on_start = on_measurements[0][0]
    off_start = off_measurements[0][0]

    width, height = 1400, 880
    left, right = 95, 40
    plot_width = width - left - right
    threads_top, threads_height = 140, 330
    load_top, load_height = 540, 220

    def on_x(ms: float) -> float:
        return ms - on_start

    def off_x(ms: float) -> float:
        return ms - off_start

    max_t = max(on[-1].ms - on_start, off[-1].ms - off_start)
    max_threads = max(
        max(sample.platform for sample in on),
        max(sample.platform for sample in off),
        max(sample.mounted for sample in on),
        max(sample.queued for sample in on),
    )
    axis_threads = nice_max(max_threads, 20)
    axis_load = nice_max(max(sample.active for sample in on + off), 20)

    def sx(value: float) -> float:
        return left + (value / max_t) * plot_width if max_t else left

    def sy_threads(value: float) -> float:
        return threads_top + threads_height - (value / axis_threads) * threads_height

    def sy_load(value: float) -> float:
        return load_top + load_height - (value / axis_load) * load_height

    svg: list[str] = []
    svg.append(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}">\n'
    )
    svg.append(rect(0, 0, width, height, "#ffffff"))
    svg.append(text(width / 2, 32, "Virtual threads ON vs OFF под одинаковой нагрузкой", 21,
                    "#111", "middle", "bold"))
    svg.append(text(width / 2, 58, "Spring Boot 4 / JDK 25 · JMH external mode · 1000 × POST /chat "
                                   "на итерацию · concurrency = 100 · response delay = 1 ms", 13,
                    "#555", "middle"))

    # legend
    legend = [
        (left, 88, "Platform threads — VT ON", "#1f77b4", None),
        (left + 340, 88, "Platform threads — VT OFF", "#d62728", None),
        (left + 700, 88, "Mounted virtual threads — VT ON", "#2ca02c", "7,4"),
        (left, 112, "Queued virtual threads — VT ON", "#ff7f0e", "7,4"),
        (left + 340, 112, "Active requests — VT ON", "#9467bd", None),
        (left + 700, 112, "Active requests — VT OFF", "#7f7f7f", "7,4"),
    ]
    for x, y, label, color, dash in legend:
        svg.append(rect(x, y - 10, 13, 12, color))
        svg.append(text(x + 19, y, label, 12, "#333"))
        if dash:
            svg.append(line(x + 1, y - 4, x + 12, y - 4, "#ffffff", 1.2))

    # load bands
    for index, (start, end) in enumerate(on_measurements):
        x1, x2 = sx(on_x(start)), sx(on_x(end))
        for top, height_ in ((threads_top, threads_height), (load_top, load_height)):
            svg.append(rect(x1, top, max(0.0, x2 - x1), height_, "#4c78a8", "none", 0, 0.09))
        svg.append(text((x1 + x2) / 2, threads_top + 18, f"measurement {index}", 11, "#666", "middle"))

    # grids
    for value in range(0, axis_threads + 1, 20):
        y = sy_threads(value)
        svg.append(line(left, y, left + plot_width, y, "#e6e6e6", 1))
        svg.append(text(left - 12, y + 4, str(value), 11, "#555", "end"))
    for value in range(0, axis_load + 1, 20):
        y = sy_load(value)
        svg.append(line(left, y, left + plot_width, y, "#e6e6e6", 1))
        svg.append(text(left - 12, y + 4, str(value), 11, "#555", "end"))

    ticks = 8
    for index in range(ticks + 1):
        value = max_t * index / ticks
        x = sx(value)
        for top, height_ in ((threads_top, threads_height), (load_top, load_height)):
            svg.append(line(x, top, x, top + height_, "#f3f3f3", 1))
        svg.append(text(x, load_top + load_height + 22, f"{value / 1000:.0f}", 11, "#555", "middle"))

    svg.append(text(18, threads_top + threads_height / 2, "threads", 13, "#333", "middle",
                    "normal", f'transform="rotate(-90 18 {fmt(threads_top + threads_height / 2)})"'))
    svg.append(text(18, load_top + load_height / 2, "active requests", 13, "#333", "middle",
                    "normal", f'transform="rotate(-90 18 {fmt(load_top + load_height / 2)})"'))
    svg.append(text(left + plot_width / 2, load_top + load_height + 56,
                    "время от начала первой measurement-итерации, с", 13, "#333", "middle"))

    def series(samples: list[Sample], x_offset: float, value, y_scale, stroke: str,
               width_: float = 2.2, dash: str | None = None) -> None:
        points = []
        for sample in samples:
            x = sx(sample.ms - x_offset if x_offset else sample.ms)
            if sample.ms - x_offset < -1:
                continue
            points.append((x, y_scale(value(sample))))
        svg.append(polyline(points, stroke, width_, dash))

    series(on, on_start, lambda s: s.platform, sy_threads, "#1f77b4")
    series(off, off_start, lambda s: s.platform, sy_threads, "#d62728")
    series(on, on_start, lambda s: s.mounted, sy_threads, "#2ca02c", 1.7, "7,4")
    series(on, on_start, lambda s: s.queued, sy_threads, "#ff7f0e", 1.7, "7,4")
    series(on, on_start, lambda s: s.active, sy_load, "#9467bd")
    series(off, off_start, lambda s: s.active, sy_load, "#7f7f7f", 1.8, "7,4")

    # direct annotations
    on_platform = max(sample.platform for sample in on)
    off_platform = max(sample.platform for sample in off)
    on_mounted = max(sample.mounted for sample in on)
    on_queued = max(sample.queued for sample in on)
    svg.append(text(left + plot_width - 12, sy_threads(on_platform) - 10,
                    f"VT ON: {on_platform} platform threads", 12, "#1f77b4", "end", "bold"))
    svg.append(text(left + plot_width - 12, sy_threads(off_platform) + 20,
                    f"VT OFF: {off_platform} platform threads", 12, "#d62728", "end", "bold"))

    gap_x = sx(on_x(on_measurements[0][1])) + 22
    svg.append(text(gap_x, threads_top + 60, "VT ON во время нагрузки:", 12, "#2f6b3a",
                    "start", "bold"))
    svg.append(text(gap_x, threads_top + 80,
                    f"mounted ≤ {on_mounted} · queued ≤ {on_queued} · carrier pool = "
                    f"{max(sample.carrier for sample in on)}", 12, "#333"))
    svg.append(text(gap_x, threads_top + 100, "блокирующий future.get() паркует", 11, "#555"))
    svg.append(text(gap_x, threads_top + 117, "виртуальные потоки, carrier свободен", 11, "#555"))

    # load equality box
    on_durations = [end - start for start, end in on_measurements]
    off_durations = [end - start for start, end in off_measurements]
    on_avg = sum(on_durations) / len(on_durations)
    off_avg = sum(off_durations) / len(off_durations)
    diff = (off_avg - on_avg) / on_avg * 100
    box_x, box_y, box_w, box_h = left, 786, plot_width, 54
    svg.append(rect(box_x, box_y, box_w, box_h, "#f7f9fc", "#cfd8e3", 1, 1, 6))
    svg.append(text(box_x + 14, box_y + 21,
                    f"Проверка одинаковой нагрузки: max active requests = "
                    f"{max(sample.active for sample in on)} в обоих прогонах; "
                    f"средняя длительность measurement-итерации "
                    f"{on_avg / 1000:.2f} с (ON) и {off_avg / 1000:.2f} с (OFF), "
                    f"отличие {diff:+.1f}% (допуск 2%).", 12, "#333"))
    svg.append(text(box_x + 14, box_y + 42,
                    f"Платформенные потоки: VT ON max {on_platform}, VT OFF max {off_platform} "
                    f"({(off_platform - on_platform) / on_platform * 100:.0f}% больше); "
                    f"mounted VT ≤ {on_mounted}, queued VT ≤ {on_queued}, carrier pool = "
                    f"{max(sample.carrier for sample in on)}.", 12, "#333"))

    svg.append("</svg>\n")
    with open(output, "w", encoding="utf-8") as handle:
        handle.write("".join(svg))

    return {
        "on_platform": on_platform,
        "off_platform": off_platform,
        "on_mounted": on_mounted,
        "on_queued": on_queued,
        "on_avg_ms": on_avg,
        "off_avg_ms": off_avg,
        "load_diff_percent": diff,
    }


def rounded(x: float, y: float, w: float, h: float, fill: str, stroke: str,
            lines: list[str], size: int = 12, title: str | None = None) -> str:
    out = [rect(x, y, w, h, fill, stroke, 1.2, 1, 8)]
    if title:
        out.append(text(x + 14, y + 20, title, size, "#111", "start", "bold"))
        for index, value in enumerate(lines):
            out.append(text(x + 14, y + 38 + index * (size + 1), value, size - 1, "#333"))
    else:
        first = y + h / 2 - (len(lines) - 1) * (size + 3) / 2 + size * 0.35
        for index, value in enumerate(lines):
            out.append(text(x + 14, first + index * (size + 3), value, size, "#333"))
    return "".join(out)


def build_uml(output: str) -> None:
    width, height = 1240, 880
    svg: list[str] = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}">\n',
        rect(0, 0, width, height, "#ffffff"),
        text(width / 2, 34, "UML-диаграмма планируемого эксперимента", 22, "#111", "middle", "bold"),
        text(width / 2, 58, "Активности и дорожки: одинаковый профиль нагрузки, два режима "
                            "VIRTUAL_THREADS_ENABLED", 13, "#555", "middle"),
    ]

    lane_a_x, lane_a_y, lane_a_w, lane_a_h = 40, 92, 760, 740
    lane_b_x, lane_b_y, lane_b_w, lane_b_h = 820, 92, 380, 740

    svg.append(rect(lane_a_x, lane_a_y, lane_a_w, lane_a_h, "#fbfcfe", "#9aa7b6", 1.2, 1, 10))
    svg.append(rect(lane_b_x, lane_b_y, lane_b_w, lane_b_h, "#fbfcfe", "#9aa7b6", 1.2, 1, 10))
    svg.append(rect(lane_a_x, lane_a_y, lane_a_w, 36, "#e8eef7", "#9aa7b6", 1.2, 1, 10))
    svg.append(rect(lane_b_x, lane_b_y, lane_b_w, 36, "#f8eceb", "#9aa7b6", 1.2, 1, 10))
    svg.append(text(lane_a_x + 16, lane_a_y + 24,
                    "JMH Client JVM — генератор нагрузки (external mode)", 13, "#243b53", "start", "bold"))
    svg.append(text(lane_b_x + 16, lane_b_y + 24,
                    "Application JVM — Spring Boot / Tomcat", 13, "#6b2b26", "start", "bold"))

    # start
    svg.append(f'<circle cx="86" cy="164" r="11" fill="#333"/>\n')
    svg.append(arrow(97, 164, 120, 164))

    steps = [
        (120, 140, 640, 48, "1. docker compose up -d", ["PostgreSQL и Kafka подняты, healthcheck = healthy"]),
        (120, 210, 640, 48, "2. Запуск приложения с VIRTUAL_THREADS_ENABLED = true",
         ["JMH external mode: приложение в отдельной JVM"]),
        (120, 280, 640, 62, "3. Прогрев и измерения",
         ["2 warmup + 3 measurement; каждая итерация — 1000 × POST /chat",
          "concurrency = 100, response delay = 1 ms"]),
        (120, 364, 640, 48, "4. Снятие нагрузки после каждой measurement",
         ["пауза 10 с (BENCH_IDLE_MILLIS); сэмплер продолжает работать"]),
    ]
    for x, y, w, h, title, lines in steps:
        svg.append(rounded(x, y, w, h, "#ffffff", "#7a93ad", lines, 12, title))
    svg.append(arrow(440, 188, 440, 210))
    svg.append(arrow(440, 258, 440, 280))
    svg.append(arrow(440, 342, 440, 364))

    # decision
    cx, cy = 440, 470
    svg.append(arrow(440, 412, 440, 436))
    svg.append(f'<polygon points="{cx},{cy - 34} {cx + 130},{cy} {cx},{cy + 34} {cx - 130},{cy}" '
               f'fill="#fff4e0" stroke="#c78a12" stroke-width="1.4"/>\n')
    svg.append(text(cx, cy - 3, "Оба режима", 13, "#7a5200", "middle", "bold"))
    svg.append(text(cx, cy + 16, "выполнены?", 12, "#7a5200", "middle"))

    # no -> switch and loop back to step 2
    svg.append(text(cx + 14, cy - 8, "нет", 11, "#9a6a00", "start"))
    svg.append(arrow(cx + 130, cy, 610, cy, "#c78a12"))
    svg.append(rounded(590, cy - 27, 200, 54, "#fff8ea", "#c78a12",
                       ["VIRTUAL_THREADS_ENABLED = false", "и повторить шаги 2–4"], 11))
    svg.append(line(790, cy, 790, 236, "#c78a12", 1.4))
    svg.append(arrow(790, 236, 760, 236, "#c78a12"))
    svg.append(text(782, 330, "повторный прогон", 11, "#9a6a00", "end"))

    # yes -> final steps
    svg.append(text(cx - 14, cy + 52, "да", 11, "#2f6b3a", "end"))
    svg.append(arrow(cx, cy + 34, cx, 560, "#3a7a46"))
    svg.append(rounded(120, 560, 640, 50, "#ffffff", "#7a93ad",
                       ["5. Совместить прогоны по старту первой measurement-итерации"], 12, None))
    svg.append(rounded(120, 628, 640, 50, "#ffffff", "#7a93ad",
                       ["6. Проверить: отличие нагрузки ≤ 2%, затем построить combined chart"], 12, None))
    svg.append(rounded(120, 696, 640, 50, "#eef7f0", "#4b8b5c",
                       ["7. Сравнить platform / mounted / queued и сделать выводы"], 12, None))
    svg.append(arrow(440, 610, 440, 628))
    svg.append(arrow(440, 678, 440, 696))
    svg.append(arrow(440, 746, 440, 760))
    svg.append(f'<circle cx="440" cy="780" r="13" fill="none" stroke="#333" stroke-width="2"/>\n')
    svg.append(f'<circle cx="440" cy="780" r="7" fill="#333"/>\n')

    # application lane content
    app_steps = [
        (845, 150, 330, 64, "Tomcat: executor запросов",
         ["VT ON: virtual thread на запрос", "VT OFF: platform thread из пула"]),
        (845, 238, 330, 64, "ChatService.chat()",
         ["responseFuture.get(timeout) —", "блокирующая точка асинхронного вызова"]),
        (845, 326, 330, 64, "Kafka listeners",
         ["ChatRequestListener: sleep(response-delay)", "ChatResponseListener: complete(future)"]),
        (845, 414, 330, 64, "ThreadUsageRecorder",
         ["сэмпл каждые 50 мс:", "platform / mounted / queued / active"]),
        (845, 502, 330, 64, "CSV + SVG",
         ["active_requests, platform_threads,", "mounted/queued_virtual_threads"]),
        (845, 590, 330, 64, "Результат",
         ["VT ON: ~54 platform, mounted ≤ 22,", "queued ≤ 64; VT OFF: ~128 platform"]),
    ]
    for x, y, w, h, title, lines in app_steps:
        svg.append(rounded(x, y, w, h, "#ffffff", "#b58a86", lines, 11, title))
    for y in (214, 302, 390, 478, 566):
        svg.append(arrow(1010, y, 1010, y + 24, "#a06a64"))

    # cross-lane interactions
    svg.append(arrow(760, 311, 845, 182, "#44608a", 1.4))
    svg.append(text(800, 232, "POST /chat", 10, "#44608a", "start"))
    svg.append(arrow(845, 534, 760, 653, "#44608a", 1.4))
    svg.append(text(806, 600, "CSV", 10, "#44608a", "start"))

    # infrastructure note
    svg.append(rect(40, 846, 1160, 24, "#f4f6f9", "#d4dbe3", 1, 1, 4))
    svg.append(text(52, 863, "Инфраструктура: PostgreSQL 17 и Kafka 3.9 в docker compose; "
                             "JMH и приложение — разные JVM (external mode); одинаковые 1000 запросов "
                             "и concurrency=100 в обоих режимах.", 11, "#555"))

    svg.append("</svg>\n")
    with open(output, "w", encoding="utf-8") as handle:
        handle.write("".join(svg))


def build_request_flow(output: str) -> None:
    """UML sequence diagram of the POST /chat request processing."""
    width, height = 1400, 840
    participants = [
        (90, ["Client", "(JMH)"]),
        (290, ["Tomcat", "ChatController"]),
        (490, ["ChatService"]),
        (690, ["PostgreSQL", "chat"]),
        (890, ["Kafka", "chat-request / chat-response"]),
        (1090, ["ChatRequestListener"]),
        (1290, ["ChatResponseListener"]),
    ]
    top, bottom = 100, 700
    svg: list[str] = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}">\n',
        rect(0, 0, width, height, "#ffffff"),
        text(width / 2, 34, "Схема обработки запроса POST /chat", 22, "#111", "middle", "bold"),
        text(width / 2, 58, "REST-контроллер → ChatService → асинхронный вызов Kafka → "
                            "блокирующее ожидание → ответ клиенту", 13, "#555", "middle"),
    ]

    # blocking interval behind the ChatService lifeline
    svg.append(rect(478, 300, 24, 270, "#f58518", "none", 0, 0.16, 6))
    svg.append(text(466, 435, "future.get(timeout)", 10, "#a65e00", "middle", "normal",
                    'transform="rotate(-90 466 435)"'))

    for x, lines in participants:
        box_w = 176
        svg.append(rect(x - box_w / 2, top, box_w, 54, "#eef2f7", "#7a93ad", 1.2, 1, 7))
        first = top + 22
        for index, value in enumerate(lines):
            size = 12 if index == 0 else 10
            weight = "bold" if index == 0 else "normal"
            svg.append(text(x, first + index * 15, value, size, "#243b53", "middle", weight))
        svg.append(line(x, top + 54, x, bottom, "#b8c4d1", 1.2, "5,5"))

    def message(x1: float, x2: float, y: float, label: str, color: str = "#333",
                dashed: bool = False, number: str | None = None) -> None:
        svg.append(arrow(x1, y, x2, y, color, 1.5, "6,4" if dashed else None))
        middle = (x1 + x2) / 2
        value = f"{number}. {label}" if number else label
        label_w = len(value) * 6.2 + 12
        svg.append(rect(middle - label_w / 2, y - 18, label_w, 15, "#ffffff", "none", 0, 1, 2))
        svg.append(text(middle, y - 6, value, 11, color, "middle"))

    message(90, 290, 200, "POST /chat", number="1")
    message(290, 490, 240, "chat(request)", number="2")
    message(490, 690, 280, "INSERT chat (id, request, request_time)", number="3")
    message(490, 890, 320, "send chat-request (id, request, request_time)", number="4")
    message(890, 1090, 360, "consume", number="5")

    # self-message: simulated processing delay
    svg.append(line(1090, 388, 1160, 388, "#8a4b00", 1.4))
    svg.append(line(1160, 388, 1160, 406, "#8a4b00", 1.4))
    svg.append(arrow(1160, 406, 1090, 406, "#8a4b00", 1.4))
    svg.append(text(1104, 381, "6. sleep(response-delay)", 11, "#8a4b00"))
    svg.append(text(1104, 422, "имитация async-обработки", 10, "#8a4b00"))

    message(1090, 890, 446, "send chat-response (id, «Ответ <uuid>»)", number="7")
    message(890, 1290, 486, "consume", number="8")
    message(1290, 690, 526, "UPDATE chat (response, response_time)", number="9")
    message(1290, 490, 566, "complete(future) через ChatResponseRegistry", "#6a3d9a", True, "10")
    message(490, 290, 606, "return response", number="11")
    message(290, 90, 646, "HTTP 200 «Ответ <uuid>»", number="12")

    # blocking note
    svg.append(rect(530, 330, 220, 82, "#fff7ec", "#e0a04a", 1.1, 1, 6))
    svg.append(text(542, 350, "future.get(timeout)", 12, "#7a5200", "start", "bold"))
    svg.append(text(542, 368, "VT ON: virtual thread припаркован,", 10, "#7a5200"))
    svg.append(text(542, 383, "carrier свободен для других", 10, "#7a5200"))
    svg.append(text(542, 401, "VT OFF: OS-поток заблокирован", 10, "#7a5200"))

    # bottom notes
    svg.append(rect(40, 724, 1320, 82, "#f7f9fc", "#cfd8e3", 1, 1, 6))
    svg.append(text(56, 746, "Виртуальные потоки:", 12, "#111", "start", "bold"))
    svg.append(text(56, 764, "в точке future.get() виртуальный поток паркуется и "
                             "размонтируется с carrier; carrier продолжает обслуживать другие запросы.", 11, "#333"))
    svg.append(text(56, 782, "Без виртуальных потоков:", 12, "#111", "start", "bold"))
    svg.append(text(56, 800, "платформенный поток Tomcat остаётся заблокированным до вызова "
                             "complete(future) из ChatResponseListener.", 11, "#333"))

    svg.append("</svg>\n")
    with open(output, "w", encoding="utf-8") as handle:
        handle.write("".join(svg))


def main() -> None:
    base = os.path.dirname(os.path.abspath(__file__))
    on_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(base, "vt-on.csv")
    off_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(base, "vt-off.csv")
    output_dir = sys.argv[3] if len(sys.argv) > 3 else base

    stats = build_combined(on_path, off_path, os.path.join(output_dir, "vt-combined.svg"))
    build_uml(os.path.join(output_dir, "experiment-plan-uml.svg"))
    build_request_flow(os.path.join(output_dir, "request-flow.svg"))

    print("Combined chart written to", os.path.join(output_dir, "vt-combined.svg"))
    print("UML diagram written to", os.path.join(output_dir, "experiment-plan-uml.svg"))
    print("Request flow written to", os.path.join(output_dir, "request-flow.svg"))
    for key, value in stats.items():
        print(f"{key}: {value:.2f}")


if __name__ == "__main__":
    main()
