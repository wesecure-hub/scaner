"""Runs inside GitHub Actions before the build. Writes src/main/assets/universe.csv
(ticker,name,sector,market) for S&P500 + Nasdaq-100, so the app needs no list download."""
import io, os, sys
import pandas as pd
import requests

UA = {"User-Agent": "Mozilla/5.0"}
rows = {}


def add(t, name, sector, mk):
    t = str(t).strip().replace(".", "-")
    if not t or t == "nan":
        return
    r = rows.setdefault(t, {"name": str(name).replace(",", " "), "sector": str(sector).replace(",", " "), "mk": set()})
    r["mk"].add(mk)


try:
    r = requests.get("https://raw.githubusercontent.com/datasets/s-and-p-500-companies/main/data/constituents.csv",
                     headers=UA, timeout=30)
    r.raise_for_status()
    df = pd.read_csv(io.StringIO(r.text))
    nc = "Security" if "Security" in df.columns else "Name"
    sc = "GICS Sector" if "GICS Sector" in df.columns else "Sector"
    for _, x in df.iterrows():
        add(x["Symbol"], x[nc], x[sc], "SP500")
    print("S&P500 ok:", len(df))
except Exception as e:
    print("S&P500 FAILED:", e)

try:
    r = requests.get("https://en.wikipedia.org/wiki/Nasdaq-100", headers=UA, timeout=30)
    r.raise_for_status()
    for t in pd.read_html(io.StringIO(r.text)):
        tc = next((c for c in ("Ticker", "Symbol") if c in t.columns), None)
        sc = next((c for c in t.columns if "Sector" in str(c)), None)
        if tc and sc and len(t) >= 90:
            nc = "Company" if "Company" in t.columns else tc
            for _, x in t.iterrows():
                add(x[tc], x[nc], x[sc], "NDX")
            print("Nasdaq-100 ok:", len(t))
            break
except Exception as e:
    print("Nasdaq-100 FAILED:", e)

if len(rows) < 300:
    sys.exit("universe too small (%d) - list download failed" % len(rows))

os.makedirs("src/main/assets", exist_ok=True)
with open("src/main/assets/universe.csv", "w", encoding="utf-8") as f:
    f.write("ticker,name,sector,market\n")
    for t, r in sorted(rows.items()):
        f.write(f'{t},{r["name"]},{r["sector"]},{"|".join(sorted(r["mk"]))}\n')
print("universe rows:", len(rows))
