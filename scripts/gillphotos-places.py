#!/usr/bin/env python3
"""Builds gillphotos' offline place list from GeoNames' cities1000.txt (CC BY 4.0, geonames.org).

    curl -O https://download.geonames.org/export/dump/cities1000.zip && unzip cities1000.zip
    python3 scripts/gillphotos-places.py cities1000.txt android/gillphotos/app/src/main/assets/places.tsv.gz

Each line: name, latitude and longitude (3 decimals, about 100 m), country code. Places with 1,000 or more people,
which includes suburbs such as Bondi. The app turns a photo's GPS into the nearest one, on the phone.
"""
import gzip
import sys

src, dst = sys.argv[1], sys.argv[2]
rows = []
with open(src, encoding="utf-8") as f:
    for line in f:
        c = line.rstrip("\n").split("\t")
        name, lat, lon, cc = c[1], float(c[4]), float(c[5]), c[8]
        rows.append(f"{name}\t{lat:.3f}\t{lon:.3f}\t{cc}\n")
rows.sort()
with gzip.GzipFile(dst, "wb", mtime=0) as out:
    out.write("".join(rows).encode("utf-8"))
print(f"{len(rows)} places")
