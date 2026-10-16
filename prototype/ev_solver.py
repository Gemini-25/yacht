"""Feasibility spike: exact max-expected-score solver for Nintendo-style Yacht.

State = (filled-category bitmask, upper sum capped at 63). Backward DP.
Usage: python3 prototype/ev_solver.py [--fh-yacht] [--4k-four] [--sim N]
"""
import itertools, math, sys, time
import numpy as np

FH_ACCEPTS_YACHT = "--fh-yacht" in sys.argv
FOURK_SUM_FOUR = "--4k-four" in sys.argv
SIM = int(sys.argv[sys.argv.index("--sim") + 1]) if "--sim" in sys.argv else 0

NCAT = 12
ROLLS = list(itertools.combinations_with_replacement(range(1, 7), 5))      # 252
KEEPS = [k for n in range(6) for k in itertools.combinations_with_replacement(range(1, 7), n)]  # 462
R_IDX = {r: i for i, r in enumerate(ROLLS)}
K_IDX = {k: i for i, k in enumerate(KEEPS)}

def counts(d):
    c = [0] * 7
    for x in d:
        c[x] += 1
    return c

def multinomial_prob(extra):
    n = len(extra)
    c = counts(extra)
    m = math.factorial(n)
    for x in c:
        m //= math.factorial(x)
    return m / 6 ** n

# keep -> roll transition matrix
T = np.zeros((len(KEEPS), len(ROLLS)))
for ki, k in enumerate(KEEPS):
    for extra in itertools.combinations_with_replacement(range(1, 7), 5 - len(k)):
        T[ki, R_IDX[tuple(sorted(k + extra))]] += multinomial_prob(extra)
assert np.allclose(T.sum(1), 1)

# roll -> its distinct sub-multisets (padded)
subs = []
for r in ROLLS:
    s = {tuple(sorted(c)) for n in range(6) for c in itertools.combinations(r, n)}
    subs.append(sorted(K_IDX[x] for x in s))
W = max(map(len, subs))
SUB = np.array([s + [s[0]] * (W - len(s)) for s in subs])                 # (252, W)

def score(r, c):
    cnt = counts(r)
    tot = sum(r)
    if c < 6:
        return cnt[c + 1] * (c + 1)
    if c == 6:
        return tot
    if c == 7:
        f = [v for v in range(1, 7) if cnt[v] >= 4]
        if not f:
            return 0
        return 4 * f[0] if FOURK_SUM_FOUR else tot
    if c == 8:
        shape = sorted(x for x in cnt if x)
        ok = shape == [2, 3] or (FH_ACCEPTS_YACHT and shape == [5])
        return tot if ok else 0
    s = set(r)
    if c == 9:
        return 15 if any(set(range(a, a + 4)) <= s for a in (1, 2, 3)) else 0
    if c == 10:
        return 30 if any(set(range(a, a + 5)) <= s for a in (1, 2)) else 0
    return 50 if max(cnt) == 5 else 0

S = np.array([[score(r, c) for c in range(NCAT)] for r in ROLLS], dtype=np.int64)  # (252,12)
U = np.arange(64)
P0 = T[K_IDX[()]]

V = np.zeros((1 << NCAT, 64))   # expected future score incl. bonus

def turn_tables(mask):
    """E3[r,u]: best value after final roll r; returns (E1,E2,E3,K1,K2) for policy use."""
    E3 = np.full((len(ROLLS), 64), -1e9)
    for c in range(NCAT):
        if mask >> c & 1:
            continue
        nm = mask | 1 << c
        if c < 6:
            s = S[:, c][:, None]
            nu = np.minimum(63, U[None, :] + s)
            bonus = np.where((U[None, :] < 63) & (U[None, :] + s >= 63), 35, 0)
            val = s + bonus + V[nm][nu]
        else:
            val = S[:, c][:, None] + V[nm][None, :]
        np.maximum(E3, val, out=E3)
    K2 = T @ E3
    E2 = K2[SUB].max(1)
    K1 = T @ E2
    E1 = K1[SUB].max(1)
    return E1, E2, E3, K1, K2

t0 = time.time()
masks = sorted(range((1 << NCAT) - 1), key=lambda m: -bin(m).count("1"))
for m in masks:
    E1 = turn_tables(m)[0]
    V[m] = P0 @ E1
print(f"rules: FH_accepts_yacht={FH_ACCEPTS_YACHT} 4K_sum_four={FOURK_SUM_FOUR}")
print(f"solve time {time.time() - t0:.1f}s, table {V.nbytes / 1e6:.1f} MB (float64)")
print(f"optimal expected score: {V[0, 0]:.4f}")

if SIM:
    rng = np.random.default_rng(1)
    cache = {}
    def roll(keep):
        k = list(keep) + list(rng.integers(1, 7, 5 - len(keep)))
        return R_IDX[tuple(sorted(int(x) for x in k))]
    scores, bonus_hits, yachts = [], 0, 0
    t0 = time.time()
    for g in range(SIM):
        mask, u, total = 0, 0, 0
        for turn in range(NCAT):
            if mask not in cache:
                cache[mask] = turn_tables(mask)
            E1, E2, E3, K1, K2 = cache[mask]
            r = roll(())
            k = SUB[r][np.argmax(K1[SUB[r], u])]
            r = roll(KEEPS[k])
            k = SUB[r][np.argmax(K2[SUB[r], u])]
            r = roll(KEEPS[k])
            best, bc = -1e9, -1
            for c in range(NCAT):
                if mask >> c & 1:
                    continue
                s = S[r, c]
                if c < 6:
                    nu = min(63, u + s)
                    b = 35 if u < 63 <= u + s else 0
                    v = s + b + V[mask | 1 << c][nu]
                else:
                    nu, b, v = u, 0, s + V[mask | 1 << c][u]
                if v > best:
                    best, bc, bb, bnu = v, c, b, nu
            total += S[r, bc] + bb
            bonus_hits += bb > 0
            yachts += bc == 11 and S[r, 11] == 50
            mask |= 1 << bc
            u = bnu
        scores.append(total)
    a = np.array(scores)
    print(f"sim {SIM} games in {time.time() - t0:.0f}s: mean {a.mean():.2f} sd {a.std():.2f} "
          f"p10 {np.percentile(a, 10):.0f} p50 {np.median(a):.0f} p90 {np.percentile(a, 90):.0f} "
          f"bonus {bonus_hits / SIM:.1%} yacht {yachts / SIM:.1%}")
