#!/usr/bin/env python3
"""
BLOCK BLAST AI BOT V8
Single-file local assistant: vision, strategy, beam/deep search, calibrated
drag-and-drop, learning history, dashboard, statistics and game-state detection.

Install:
    pip install numpy opencv-python pyautogui pillow

Run:
    python block_blast_ai_bot_v8.py

Notes:
- This is a configurable desktop assistant, not a universal one-click bot.
- Calibrate board_roi, tray_centers and board_cell_centers in the JSON settings
  created on first run. Auto Play is disabled until explicitly enabled.
- Piece shapes are entered in the dashboard. The board is scanned from screen.
- The solver assumes pieces can be placed in any order from the current tray
  and full rows/columns clear after each placement. Check your game variant.
- Auto Play performs one verified move at a time, rescans, then replans.
- It does not bypass game protections or interact with accounts.
"""

from __future__ import annotations

import json
import time
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Sequence

import numpy as np

try:
    import cv2
except Exception:
    cv2 = None

try:
    import pyautogui
    pyautogui.FAILSAFE = True
except Exception:
    pyautogui = None

try:
    import tkinter as tk
    from tkinter import ttk, messagebox
except Exception:
    tk = None
    ttk = None
    messagebox = None


BASE = Path(__file__).resolve().parent
SETTINGS_FILE = BASE / "block_blast_v8_settings.json"
STATS_FILE = BASE / "block_blast_v8_stats.json"
HISTORY_FILE = BASE / "block_blast_v8_history.json"
LEARNING_FILE = BASE / "block_blast_v8_learning.json"

DEFAULT_SETTINGS = {
    "board_size": 10,
    "board_roi": None,
    "cell_dark_threshold": 115,
    "occupied_fraction_threshold": 0.22,
    "beam_width": 100,
    "search_depth": 3,
    "tray_centers": [],
    "board_cell_centers": [],
    "drag_duration": 0.25,
    "post_drop_wait": 0.65,
    "allow_automation": False,
    "auto_rescan_after_move": True
}
DEFAULT_STATS = {
    "games_seen": 0, "moves_planned": 0, "moves_simulated": 0,
    "moves_dragged": 0, "rows_cleared": 0, "columns_cleared": 0,
    "best_score_estimate": 0.0
}
DEFAULT_LEARNING = {"ratings": [], "weights": {
    "line": 1.0, "holes": 1.0, "roughness": 1.0, "small_regions": 1.0
}}


def load_json(path: Path, defaults: dict) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(data, dict):
            result = defaults.copy()
            result.update(data)
            return result
    except Exception:
        pass
    return defaults.copy()


def save_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


@dataclass
class Move:
    piece_id: int
    row: int
    col: int
    rows: int
    cols: int
    value: float


@dataclass
class SearchResult:
    moves: list[Move]
    board: np.ndarray
    value: float
    nodes: int
    pieces_placed: int


class Board:
    def __init__(self, size: int):
        if not 5 <= int(size) <= 16:
            raise ValueError("Board size must be between 5 and 16.")
        self.size = int(size)

    def empty(self):
        return np.zeros((self.size, self.size), dtype=np.uint8)

    def check(self, board):
        arr = np.asarray(board, dtype=np.uint8)
        if arr.shape != (self.size, self.size):
            raise ValueError("Board dimensions do not match the configured size.")
        return (arr != 0).astype(np.uint8)

    @staticmethod
    def piece(shape):
        arr = np.asarray(shape, dtype=np.uint8)
        if arr.ndim != 2 or not arr.size:
            raise ValueError("A piece must be a non-empty 2D matrix.")
        arr = (arr != 0).astype(np.uint8)
        rr, cc = np.where(arr)
        if len(rr) == 0:
            raise ValueError("A piece must contain at least one block.")
        return arr[rr.min():rr.max()+1, cc.min():cc.max()+1]

    def placements(self, board, piece):
        p = self.piece(piece)
        h, w = p.shape
        for r in range(self.size-h+1):
            for c in range(self.size-w+1):
                area = board[r:r+h, c:c+w]
                if not np.any((area != 0) & (p != 0)):
                    yield r, c

    def put(self, board, piece, row, col):
        p = self.piece(piece)
        h, w = p.shape
        if row < 0 or col < 0 or row+h > self.size or col+w > self.size:
            raise ValueError("Placement is outside the board.")
        area = board[row:row+h, col:col+w]
        if np.any((area != 0) & (p != 0)):
            raise ValueError("Placement overlaps an occupied cell.")
        out = board.copy()
        out[row:row+h, col:col+w] |= p
        full_rows = np.all(out == 1, axis=1)
        full_cols = np.all(out == 1, axis=0)
        nr, nc = int(full_rows.sum()), int(full_cols.sum())
        out[full_rows, :] = 0
        out[:, full_cols] = 0
        return out, nr, nc


class Learner:
    """Persistent lightweight feedback adaptation; not a neural network."""
    def __init__(self):
        self.data = load_json(LEARNING_FILE, DEFAULT_LEARNING)

    def weights(self):
        return self.data.get("weights", DEFAULT_LEARNING["weights"])

    def feedback(self, rating: int):
        rating = max(1, min(5, int(rating)))
        self.data.setdefault("ratings", []).append({
            "time": time.strftime("%Y-%m-%d %H:%M:%S"), "rating": rating
        })
        self.data["ratings"] = self.data["ratings"][-300:]
        factor = 1.015 if rating >= 4 else 0.985 if rating <= 2 else 1.0
        weights = self.data.setdefault("weights", DEFAULT_LEARNING["weights"].copy())
        for key in weights:
            weights[key] = float(np.clip(weights[key] * factor, 0.65, 1.5))
        save_json(LEARNING_FILE, self.data)


class Strategy:
    def __init__(self, board: Board, learner: Learner):
        self.engine, self.learner = board, learner

    def holes(self, b):
        count = 0
        for c in range(self.engine.size):
            filled = np.flatnonzero(b[:, c])
            if len(filled):
                count += int(np.sum(b[filled[0]:, c] == 0))
        return count

    def roughness(self, b):
        heights = []
        for c in range(self.engine.size):
            filled = np.flatnonzero(b[:, c])
            heights.append(self.engine.size-int(filled[0]) if len(filled) else 0)
        return sum(abs(heights[i]-heights[i+1]) for i in range(len(heights)-1))

    def small_regions(self, b):
        empty = b == 0
        seen = np.zeros_like(empty, dtype=bool)
        small = 0
        for y in range(self.engine.size):
            for x in range(self.engine.size):
                if not empty[y, x] or seen[y, x]:
                    continue
                stack, n = [(y, x)], 0
                seen[y, x] = True
                while stack:
                    cy, cx = stack.pop()
                    n += 1
                    for dy, dx in ((1,0),(-1,0),(0,1),(0,-1)):
                        ny, nx = cy+dy, cx+dx
                        if 0 <= ny < self.engine.size and 0 <= nx < self.engine.size:
                            if empty[ny, nx] and not seen[ny, nx]:
                                seen[ny, nx] = True
                                stack.append((ny, nx))
                if n <= 2:
                    small += 1
        return small

    def score(self, b, lines=0):
        w = self.learner.weights()
        free = int((b == 0).sum())
        return (free + 19*lines*float(w.get("line", 1.0))
                - 8*self.holes(b)*float(w.get("holes", 1.0))
                - 1.6*self.roughness(b)*float(w.get("roughness", 1.0))
                - 2.2*self.small_regions(b)*float(w.get("small_regions", 1.0)))


class DeepBeamSearch:
    def __init__(self, engine, strategy, width=100, depth=3):
        self.engine, self.strategy = engine, strategy
        self.width, self.depth = max(1, int(width)), max(1, int(depth))

    def solve(self, board, pieces):
        start = self.engine.check(board)
        pieces = [self.engine.piece(p) for p in pieces]
        depth = min(len(pieces), self.depth)
        # Nodes are (board, accumulated score, move list, placed count).
        beam = [(start, 0.0, [], 0)]
        best = beam[0]
        expanded = 0
        for level in range(depth):
            candidates = []
            # Try every remaining piece at each depth; tray order is not assumed.
            for state, total, path, _ in beam:
                for pid, piece in enumerate(pieces):
                    if pid in [m.piece_id for m in path]:
                        continue
                    for row, col in self.engine.placements(state, piece):
                        expanded += 1
                        nxt, nr, nc = self.engine.put(state, piece, row, col)
                        line_count = nr + nc
                        value = total + self.strategy.score(nxt, line_count) + 7*line_count
                        candidates.append((nxt, value,
                            path + [Move(pid, row, col, nr, nc, value)], len(path)+1))
            if not candidates:
                break
            candidates.sort(key=lambda node: node[1], reverse=True)
            # Keep diverse states by board signature.
            unique, beam = set(), []
            for node in candidates:
                signature = node[0].tobytes()
                if signature in unique:
                    continue
                unique.add(signature)
                beam.append(node)
                if len(beam) >= self.width:
                    break
            best = beam[0]
        return SearchResult(best[2], best[0].copy(), float(best[1]), expanded, best[3])


class Vision:
    def __init__(self, settings, size):
        self.settings, self.size = settings, size
        self.previous = None

    def screenshot(self):
        if pyautogui is None:
            raise RuntimeError("Install pyautogui and pillow to capture the screen.")
        rgb = np.asarray(pyautogui.screenshot())
        return cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR) if cv2 is not None else rgb

    def scan(self, frame):
        roi = self.settings.get("board_roi")
        if not roi or len(roi) != 4:
            raise ValueError("Set board_roi=[left,top,width,height] in settings first.")
        left, top, width, height = map(int, roi)
        crop = frame[top:top+height, left:left+width]
        if crop.size == 0:
            raise ValueError("Board ROI is outside the screenshot.")
        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY) if cv2 is not None and crop.ndim == 3 else (
            np.mean(crop[:, :, :3], axis=2) if crop.ndim == 3 else crop)
        threshold = float(self.settings.get("cell_dark_threshold", 115))
        fraction_needed = float(self.settings.get("occupied_fraction_threshold", 0.22))
        cell_h, cell_w = height/self.size, width/self.size
        board = np.zeros((self.size, self.size), dtype=np.uint8)
        for r in range(self.size):
            for c in range(self.size):
                y0, y1 = int((r+.25)*cell_h), int((r+.75)*cell_h)
                x0, x1 = int((c+.25)*cell_w), int((c+.75)*cell_w)
                patch = gray[y0:y1, x0:x1]
                if patch.size:
                    board[r, c] = int(float(np.mean(patch < threshold)) >= fraction_needed)
        changed = self.previous is None or not np.array_equal(self.previous, board)
        self.previous = board.copy()
        return board, changed

    @staticmethod
    def game_over(board, pieces, engine):
        return bool(pieces) and all(not any(engine.placements(board, p)) for p in pieces)


class AutoPlay:
    def __init__(self, settings):
        self.settings = settings
        self.enabled = bool(settings.get("allow_automation", False))
        self.stop_event = threading.Event()

    def stop(self):
        self.stop_event.set()
        if pyautogui is not None:
            try:
                pyautogui.mouseUp()
            except Exception:
                pass

    def drag(self, piece_index, row, col, size):
        if not self.enabled:
            raise RuntimeError("Auto Play is disabled. Enable it only after calibration.")
        if pyautogui is None:
            raise RuntimeError("Install pyautogui and pillow first.")
        sources = self.settings.get("tray_centers", [])
        targets = self.settings.get("board_cell_centers", [])
        if piece_index >= len(sources):
            raise ValueError("Missing tray_centers entry for this piece.")
        index = row*size + col
        if index >= len(targets):
            raise ValueError("Missing board_cell_centers entry for this target cell.")
        sx, sy = map(int, sources[piece_index])
        tx, ty = map(int, targets[index])
        self.stop_event.clear()
        pyautogui.moveTo(sx, sy)
        pyautogui.mouseDown()
        try:
            pyautogui.moveTo(tx, ty, duration=float(self.settings.get("drag_duration", .25)))
            time.sleep(float(self.settings.get("post_drop_wait", .65)))
            if self.stop_event.is_set():
                return
            pyautogui.mouseUp()
        finally:
            try:
                pyautogui.mouseUp()
            except Exception:
                pass


class App:
    SAMPLE = ["11,11", "111", "10,11"]

    def __init__(self):
        if tk is None:
            raise RuntimeError("Tkinter is unavailable. Use desktop Python with Tk support.")
        self.settings = load_json(SETTINGS_FILE, DEFAULT_SETTINGS)
        self.stats = load_json(STATS_FILE, DEFAULT_STATS)
        self.size = int(self.settings["board_size"])
        self.engine = Board(self.size)
        self.learner = Learner()
        self.strategy = Strategy(self.engine, self.learner)
        self.solver = DeepBeamSearch(self.engine, self.strategy,
                                     self.settings["beam_width"], self.settings["search_depth"])
        self.vision = Vision(self.settings, self.size)
        self.auto = AutoPlay(self.settings)
        self.board = self.engine.empty()
        self.piece_vars = []
        self.cells = []
        self.result = None
        self.root = tk.Tk()
        self.root.title("Block Blast AI Bot V8")
        self.root.geometry("1080x780")
        self.status = tk.StringVar(value="Ready. Scan the board or edit cells, enter tray pieces, then Analyze.")
        self.build()

    def build(self):
        header = ttk.Frame(self.root, padding=8)
        header.pack(fill="x")
        ttk.Label(header, text="BLOCK BLAST AI BOT V8", font=("Arial", 18, "bold")).pack(side="left")
        ttk.Label(header, text="Vision • Strategy • Deep Beam Search • Auto Play • Learning").pack(side="right")
        main = ttk.Frame(self.root, padding=8)
        main.pack(fill="both", expand=True)
        left = ttk.LabelFrame(main, text="Board editor / vision", padding=8)
        left.pack(side="left", fill="y")
        grid = ttk.Frame(left)
        grid.pack()
        for r in range(self.size):
            row = []
            for c in range(self.size):
                b = tk.Button(grid, width=2, height=1, relief="flat",
                              command=lambda y=r, x=c: self.toggle(y, x))
                b.grid(row=r, column=c, padx=1, pady=1)
                row.append(b)
            self.cells.append(row)
        actions = ttk.Frame(left)
        actions.pack(fill="x", pady=6)
        ttk.Button(actions, text="Clear", command=self.clear).pack(side="left")
        ttk.Button(actions, text="Scan screen", command=self.scan).pack(side="left", padx=3)
        ttk.Button(actions, text="Save settings", command=self.save).pack(side="left")
        ttk.Label(left, text="ROI: left,top,width,height").pack(anchor="w")
        self.roi_var = tk.StringVar(value="" if not self.settings.get("board_roi") else
                                    ",".join(map(str, self.settings["board_roi"])))
        ttk.Entry(left, textvariable=self.roi_var, width=27).pack(fill="x")
        ttk.Button(left, text="Save ROI", command=self.save_roi).pack(anchor="w", pady=4)

        right = ttk.Frame(main)
        right.pack(side="left", fill="both", expand=True, padx=(10, 0))
        pieces_box = ttk.LabelFrame(right, text="Tray pieces (rows separated by commas; 1=block, 0=empty)", padding=8)
        pieces_box.pack(fill="x")
        for i, default in enumerate(self.SAMPLE):
            line = ttk.Frame(pieces_box)
            line.pack(fill="x", pady=2)
            ttk.Label(line, text=f"Piece {i+1}", width=9).pack(side="left")
            var = tk.StringVar(value=default)
            self.piece_vars.append(var)
            ttk.Entry(line, textvariable=var).pack(side="left", fill="x", expand=True)

        controls = ttk.LabelFrame(right, text="Analysis and execution", padding=8)
        controls.pack(fill="x", pady=7)
        ttk.Button(controls, text="Analyze best sequence", command=self.analyze).pack(side="left")
        ttk.Button(controls, text="Apply first move (simulator)", command=self.apply_first).pack(side="left", padx=4)
        ttk.Button(controls, text="Auto Play one move", command=self.auto_one).pack(side="left")
        ttk.Button(controls, text="STOP", command=self.stop).pack(side="left", padx=4)

        settings_box = ttk.LabelFrame(right, text="Automation / learning", padding=8)
        settings_box.pack(fill="x")
        self.auto_var = tk.BooleanVar(value=self.auto.enabled)
        ttk.Checkbutton(settings_box, text="Enable calibrated screen automation (off by default)",
                        variable=self.auto_var, command=self.toggle_auto).pack(anchor="w")
        ttk.Label(settings_box, text="Coordinates: tray_centers and board_cell_centers in settings JSON.").pack(anchor="w")
        rating_line = ttk.Frame(settings_box)
        rating_line.pack(anchor="w", pady=3)
        ttk.Label(rating_line, text="Rate this strategy (1-5):").pack(side="left")
        self.rating_var = tk.StringVar(value="3")
        ttk.Combobox(rating_line, textvariable=self.rating_var, values=["1","2","3","4","5"],
                     width=4, state="readonly").pack(side="left", padx=4)
        ttk.Button(rating_line, text="Save feedback", command=self.feedback).pack(side="left")

        output_box = ttk.LabelFrame(right, text="Plan, detection and statistics", padding=8)
        output_box.pack(fill="both", expand=True, pady=7)
        self.output = tk.Text(output_box, height=16, wrap="word")
        self.output.pack(fill="both", expand=True)
        ttk.Label(self.root, textvariable=self.status, relief="sunken", anchor="w", padding=5).pack(fill="x", side="bottom")
        self.refresh()

    def parse_pieces(self):
        pieces = []
        for var in self.piece_vars:
            raw = var.get().strip()
            if not raw:
                continue
            rows = [x.strip().replace(" ", "") for x in raw.split(",") if x.strip()]
            if not rows or len(set(map(len, rows))) != 1 or any(set(x)-{"0","1"} for x in rows):
                raise ValueError("Piece format is invalid. Example: 110,010")
            pieces.append(Board.piece([[int(ch) for ch in row] for row in rows]))
        if not pieces:
            raise ValueError("Enter at least one tray piece.")
        return pieces

    def refresh(self):
        for r in range(self.size):
            for c in range(self.size):
                occupied = bool(self.board[r, c])
                self.cells[r][c].configure(bg="#20a58a" if occupied else "#29343b",
                                           fg="white", text="■" if occupied else " ")

    def toggle(self, r, c):
        self.board[r, c] ^= 1
        self.result = None
        self.refresh()

    def clear(self):
        self.board = self.engine.empty()
        self.result = None
        self.refresh()
        self.status.set("Board cleared.")

    def scan(self):
        try:
            board, changed = self.vision.scan(self.vision.screenshot())
            self.board = board
            self.refresh()
            self.status.set("Board scanned; verify the detected cells. Changed: " + str(changed))
            self.stats["games_seen"] = int(self.stats.get("games_seen", 0)) + int(changed)
        except Exception as exc:
            messagebox.showerror("Vision scan", str(exc))

    def save_roi(self):
        try:
            values = [int(x.strip()) for x in self.roi_var.get().split(",")]
            if len(values) != 4 or values[2] <= 0 or values[3] <= 0:
                raise ValueError("Enter left,top,width,height with positive width and height.")
            self.settings["board_roi"] = values
            self.save()
        except Exception as exc:
            messagebox.showerror("ROI", str(exc))

    def analyze(self):
        try:
            pieces = self.parse_pieces()
            self.result = self.solver.solve(self.board, pieces)
            self.stats["moves_planned"] = int(self.stats.get("moves_planned", 0)) + len(self.result.moves)
            self.stats["best_score_estimate"] = max(float(self.stats.get("best_score_estimate", 0)),
                                                      self.result.value)
            self.output.delete("1.0", "end")
            self.output.insert("end", f"Estimated strategy value: {self.result.value:.2f}\n")
            self.output.insert("end", f"Search nodes: {self.result.nodes}\n")
            self.output.insert("end", f"Pieces placed in plan: {self.result.pieces_placed}\n\n")
            if not self.result.moves:
                self.output.insert("end", "No legal placement found.\n")
            for m in self.result.moves:
                self.output.insert("end", f"Piece {m.piece_id+1} -> row {m.row}, col {m.col}; "
                    f"clear rows={m.rows}, columns={m.cols}\n")
            self.output.insert("end", "\nPredicted final board:\n" + self.board_text(self.result.board))
            self.status.set("Analysis complete.")
            self.save()
        except Exception as exc:
            messagebox.showerror("Analysis", str(exc))

    @staticmethod
    def board_text(board):
        return "\n".join("".join("■" if x else "·" for x in row) for row in board)

    def apply_first(self):
        try:
            pieces = self.parse_pieces()
            if self.result is None:
                self.result = self.solver.solve(self.board, pieces)
            if not self.result.moves:
                self.status.set("No legal move.")
                return
            move = self.result.moves[0]
            self.board, nr, nc = self.engine.put(self.board, pieces[move.piece_id], move.row, move.col)
            self.stats["moves_simulated"] = int(self.stats.get("moves_simulated", 0)) + 1
            self.stats["rows_cleared"] = int(self.stats.get("rows_cleared", 0)) + nr
            self.stats["columns_cleared"] = int(self.stats.get("columns_cleared", 0)) + nc
            self.result = None
            self.refresh()
            self.status.set("Move applied to simulator only.")
            self.save()
        except Exception as exc:
            messagebox.showerror("Simulator", str(exc))

    def auto_one(self):
        try:
            pieces = self.parse_pieces()
            result = self.solver.solve(self.board, pieces)
            if not result.moves:
                self.status.set("No legal move found.")
                return
            move = result.moves[0]
            self.auto.drag(move.piece_id, move.row, move.col, self.size)
            self.stats["moves_dragged"] = int(self.stats.get("moves_dragged", 0)) + 1
            self.status.set("Drag sent. Rescan the board and confirm before the next move.")
            if self.settings.get("auto_rescan_after_move", True):
                self.root.after(int(float(self.settings.get("post_drop_wait", .65))*1000),
                                self.scan)
            self.save()
        except Exception as exc:
            messagebox.showerror("Auto Play", str(exc))

    def toggle_auto(self):
        self.settings["allow_automation"] = bool(self.auto_var.get())
        self.auto.enabled = self.settings["allow_automation"]
        self.save()
        self.status.set("Automation enabled; confirm calibration." if self.auto.enabled else "Automation disabled.")

    def stop(self):
        self.auto.stop()
        self.status.set("Emergency stop sent.")

    def feedback(self):
        try:
            self.learner.feedback(int(self.rating_var.get()))
            self.status.set("Feedback saved; heuristic weights updated conservatively.")
        except Exception as exc:
            messagebox.showerror("Learning", str(exc))

    def save(self):
        save_json(SETTINGS_FILE, self.settings)
        save_json(STATS_FILE, self.stats)
        save_json(HISTORY_FILE, {"saved_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                                 "statistics": self.stats})
        self.status.set("Settings and statistics saved.")

    def close(self):
        self.stop()
        self.save()
        self.root.destroy()

    def run(self):
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.mainloop()


def self_test():
    engine = Board(10)
    learner = Learner()
    strategy = Strategy(engine, learner)
    solver = DeepBeamSearch(engine, strategy, 80, 3)
    board = engine.empty()
    board[9, :7] = 1
    board[8, :4] = 1
    pieces = [np.array([[1,1],[1,1]]), np.array([[1,1,1]]), np.array([[1,0],[1,1]])]
    result = solver.solve(board, pieces)
    print("Block Blast AI Bot V8 self-test")
    print("Nodes:", result.nodes, "| Planned pieces:", result.pieces_placed)
    for move in result.moves:
        print(f"piece {move.piece_id+1}: row={move.row}, col={move.col}, "
              f"rows={move.rows}, columns={move.cols}")


def main():
    if tk is None:
        print("Tkinter unavailable; running planner self-test.")
        self_test()
    else:
        App().run()


if __name__ == "__main__":
    main()
