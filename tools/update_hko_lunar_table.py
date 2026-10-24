"""显式运行时下载天文台历表并更新月首索引；不构建、不启动应用。

约 200 个文本文件，仅保存在内存。只在所有年份及逐日校验通过后修改 Kotlin 数据表。
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
from pathlib import Path
import re
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
TABLE = ROOT / "app/src/main/java/com/autosend/utils/HkoLunarCalendar.kt"
EPOCH = date(1970, 1, 1)
MONTHS = ["正月", "二月", "三月", "四月", "五月", "六月",
          "七月", "八月", "九月", "十月", "十一月", "十二月"]
DAYS = ["初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"]


def read_year(year):
    url = f"https://www.hko.gov.hk/tc/gts/time/calendar/text/files/T{year}c.txt"
    with urlopen(url, timeout=30) as response:
        text = response.read().decode("utf-8-sig")
    rows = [(date(int(y), int(m), int(d)), token) for y, m, d, token in
            re.findall(r"(\d{4})年(\d+)月(\d+)日\s+(\S+)", text)]
    expected = (date(year + 1, 1, 1) - date(year, 1, 1)).days
    if len(rows) != expected:
        raise ValueError(f"{year}: expected {expected} days, got {len(rows)}")
    for index, (solar, _) in enumerate(rows):
        if solar != date(year, 1, 1) + timedelta(days=index):
            raise ValueError(f"{year}: missing or out-of-order date")
    return rows


def encode(rows):
    # 1901-01-01 为十一月十一，补上范围前的月首，供第一段日期查询。
    starts = [((date(1901, 1, 1) - EPOCH).days - 10) * 32 + 11]
    month, leap = 11, False
    for solar, token in rows:
        day_number = (solar - EPOCH).days
        if token.endswith("月"):
            leap = token.startswith("閏")
            month = MONTHS.index(token.removeprefix("閏")) + 1
            if day_number - (starts[-1] >> 5) not in (29, 30):
                raise ValueError(f"{solar}: invalid lunar month length")
            previous = starts[-1] & 31
            previous_month, previous_leap = previous & 15, bool(previous & 16)
            if not ((leap and not previous_leap and month == previous_month) or
                    (not leap and month == previous_month % 12 + 1)):
                raise ValueError(f"{solar}: invalid lunar month sequence")
            starts.append(day_number * 32 + month + (16 if leap else 0))
            day = 1
        else:
            day = DAYS.index(token) + 1
        if day != day_number - (starts[-1] >> 5) + 1:
            raise ValueError(f"{solar}: inconsistent lunar day")
    return starts


def main():
    with ThreadPoolExecutor(max_workers=6) as executor:
        rows = [row for year in executor.map(read_year, range(1901, 2101)) for row in year]
    starts = encode(rows)
    body = ",\n".join("        " + ", ".join(map(str, starts[i:i + 12]))
                      for i in range(0, len(starts), 12))
    source = TABLE.read_text(encoding="utf-8")
    source, replacements = re.subn(r"(private val monthStarts = intArrayOf\(\n).*?(\n    \))",
                                   lambda match: match[1] + body + match[2], source, flags=re.S)
    if replacements != 1:
        raise ValueError("Cannot locate the lunar month index")
    TABLE.write_text(source, encoding="utf-8")
    print(f"Validated {len(rows)} official dates; wrote {len(starts)} month starts")


if __name__ == "__main__":
    main()
