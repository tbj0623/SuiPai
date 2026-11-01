#!/usr/bin/env python3
"""扫 APK 里每个方法的字节码大小，找出会超 ART JIT 上限的巨型方法。

为什么看这个数：一个 @Composable 长到几千行会编出一个**单方法几十万条指令**的字节码，
ART 的 JIT 有指令数上限，超了就**永远不编译、全程解释执行**，日志上表现为
  Method exceeds compiler instruction limit: 34512 in void ...GalleryScreenKt.GalleryScreen(...)
  Choreographer: Skipped 323 frames!
这是随拍冷启要 9~20 秒的真正原因，而它在编译期、lint、单测里全都看不见。

    python3 tools/dex_method_sizes.py app/build/outputs/apk/debug/app-debug.apk [--pkg com.suipai]
"""

import argparse
import io
import re
import struct
import sys
import zipfile

MAX_U4 = 4


def uleb(data: bytes, off: int):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def parse_dex(name: str, data: bytes, out: list):
    (string_ids_size, string_ids_off) = struct.unpack_from("<II", data, 0x38)
    (type_ids_size, type_ids_off) = struct.unpack_from("<II", data, 0x40)
    (proto_ids_size, proto_ids_off) = struct.unpack_from("<II", data, 0x48)
    (method_ids_size, method_ids_off) = struct.unpack_from("<II", data, 0x58)
    (class_defs_size, class_defs_off) = struct.unpack_from("<II", data, 0x60)

    # string_data_item: uleb128 utf16_size, then MUTF-8 bytes NUL-terminated。
    # 必须缓存、且只切需要的那一段：写成 data[start:].split(NUL) 会把后半截整个拷贝一遍，
    # 10MB 的 dex 上就是 O(n^2)（第一版卡在这儿 5 分钟没跑完）。
    strings: dict[int, str] = {}

    def string(i: int) -> str:
        hit = strings.get(i)
        if hit is not None:
            return hit
        (soff,) = struct.unpack_from("<I", data, string_ids_off + i * 4)
        _, start = uleb(data, soff)
        val = data[start : data.find(b"\x00", start)].decode("utf-8", "replace")
        strings[i] = val
        return val

    def type_desc(i: int) -> str:
        (sidx,) = struct.unpack_from("<I", data, type_ids_off + i * 4)
        return string(sidx)

    def proto_shorty(i: int) -> str:
        # proto_id_item = shorty_idx(u32) return_type_idx(u32) parameters_off(u32)
        # —— 第一个才是字符串索引；把第三个当名字读会直接读出越界索引。
        (shorty_idx,) = struct.unpack_from("<I", data, proto_ids_off + i * 12)
        return string(shorty_idx)

    # method_ids: class_idx(u16) proto_idx(u16) name_idx(u32)
    methods = []
    for i in range(method_ids_size):
        cidx, pidx, nidx = struct.unpack_from("<HHI", data, method_ids_off + i * 8)
        methods.append((type_desc(cidx), string(nidx), pidx))

    for i in range(class_defs_size):
        base = class_defs_off + i * 32
        cidx, _, _, _, _, _, cdata_off, _ = struct.unpack_from("<IIIIIIII", data, base)
        if not cdata_off:
            continue
        off = cdata_off
        nsf, off = uleb(data, off)                    # static_fields_size
        nif, off = uleb(data, off)                    # instance_fields_size
        ndm, off = uleb(data, off)                   # direct_methods_size
        nvm, off = uleb(data, off)                   # virtual_methods_size
        for _ in range(nsf + nif):                   # 两类字段都要跳过：encoded_field = idx_diff + access_flags
            _, off = uleb(data, off)
            _, off = uleb(data, off)
        for count in (ndm, nvm):
            midx = 0
            for _ in range(count):
                d, off = uleb(data, off)
                midx += d
                _, off = uleb(data, off)             # access_flags
                code_off, off = uleb(data, off)
                if not code_off:
                    continue
                insns_size = struct.unpack_from("<I", data, code_off + 12)[0]  # 2 字节为单位
                desc, nname, pidx = methods[midx]
                out.append((insns_size * 2, desc[1:-1].replace("/", "."), nname, proto_shorty(pidx), name))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--pkg", default="com.suipai", help="只看这个包前缀下的方法")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--limit", type=int, default=8000, help="ART JIT 指令上限的近似字节数门槛")
    a = ap.parse_args()

    rows = []
    with zipfile.ZipFile(a.apk) as z:
        for n in z.namelist():
            if re.fullmatch(r"classes\d*\.dex", n):
                parse_dex(n, z.read(n), rows)

    rows.sort(reverse=True)
    over = [r for r in rows if r[0] >= a.limit]
    mine = [r for r in over if a.pkg in r[1]]
    others = [r for r in over if a.pkg not in r[1]]

    print(f"{a.apk}: {len(rows)} 个有代码的方法，{len(over)} 个字节码 ≥ {a.limit}B")
    print(f"\n{'bytes':>9}  method")
    for size, cls, name, shorty, dex in mine[: a.top]:
        print(f"{size:9d}  {cls}.{name}  {shorty}  [{dex}]")
    if not mine:
        print("   (没有本包方法超限)")
    if others:
        print(f"\n另有 {len(others)} 个非本包方法超限，最大：")
        for size, cls, name, _s, dex in others[:3]:
            print(f"{size:9d}  {cls}.{name}  [{dex}]")
    return 1 if mine else 0


if __name__ == "__main__":
    sys.exit(main())
