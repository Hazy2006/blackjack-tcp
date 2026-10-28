import select, socket, random, time

PORT = 5050
START_BANKROLL = 3000
MIN_BET = 100
GOAL = 20000
MAX_PLAYERS = 5   # the GUI has 5 seats

# all times in seconds
TURN_TIME = 20
RESULT_PAUSE = 5
SEND_TIMEOUT = 2

RANKS = ["A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"]
SUITS = ["S", "H", "D", "C"]

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.setblocking(0)
s.bind(('0.0.0.0', PORT))
s.listen(5)

# Each client socket has a buffer (TCP is a stream, so a recv() can end mid-line)
# and a player: {"name", "bankroll", "bet", "hand", "status": "active" or "spectator"}.
ins = [s]
buffers = {}
players = {}

# state is WAITING, BETTING, PLAYING or RESULTS. One player acts at a time:
# `current` is that player's socket, `queue` holds the ones still to come, and
# `deadline` is when the current turn (or the results pause) ends.
state = "WAITING"
queue = []
current = None
deadline = 0
deck = []
dealer = []
winners = []


def lan_ip():
    """The address other devices use to reach this PC. Connecting a UDP socket sends nothing;
    it only makes the OS choose the network card it would use to reach the internet."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


def new_deck():
    d = [r + su for r in RANKS for su in SUITS]
    random.shuffle(d)
    return d


def hand_value(cards):
    total = 0
    aces = 0
    for c in cards:
        rank = c[:-1]
        if rank == "A":
            total += 11
            aces += 1
        elif rank in ("J", "Q", "K"):
            total += 10
        else:
            total += int(rank)
    while total > 21 and aces > 0:
        total -= 10
        aces -= 1
    return total


def send(ss, line):
    """Sends one protocol line. Never raises: a broken client is cleaned up by the main loop."""
    try:
        ss.sendall((line + "\n").encode())
    except OSError:
        # the client is gone or stopped reading (sendall timed out); shut the socket down,
        # then select() reports it as readable, recv() returns b"" and it is removed as usual
        try:
            ss.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass


def seated():
    return [ss for ss in players if players[ss]["name"] is not None]


def active():
    return [ss for ss in seated() if players[ss]["status"] == "active"]


def in_round():
    return [ss for ss in active() if players[ss]["bet"] > 0]


def broadcast(line, skip=None):
    for ss in seated():
        if ss != skip:
            send(ss, line)


def draw():
    if len(deck) == 0:
        deck.extend(new_deck())
    return deck.pop()


def unique_name(wanted):
    """Letters, digits and _ only, since spaces and commas separate protocol fields. Max 12, no duplicates."""
    name = "".join(ch for ch in wanted if ch.isalnum() or ch == "_")[:12] or "Player"
    taken = [players[ss]["name"] for ss in seated()]
    base, n = name, 2
    while name in taken:
        name = base + str(n)
        n += 1
    return name


def show(cards):
    return ",".join(cards) + " (" + str(hand_value(cards)) + ")"


def remove_client(ss):
    """Forgets a disconnected client. If it was their turn, the turn passes on so the table never waits for them."""
    global current
    print("Client is gone")
    name = players[ss]["name"] if ss in players else None
    if ss in ins:
        ins.remove(ss)
    buffers.pop(ss, None)
    players.pop(ss, None)
    ss.close()
    if name is not None:
        broadcast("MSG " + name + " left the table")
    if ss == current:
        current = None
        if state == "BETTING":
            next_bettor()
        else:
            next_player()


def start_round():
    global state
    if len(active()) == 0:
        game_over()
        return
    state = "BETTING"
    for ss in seated():
        players[ss]["bet"] = 0
        players[ss]["hand"] = []
    queue[:] = active()
    broadcast("MSG --- New round ---")
    next_bettor()


def next_bettor():
    """Gives the bet turn to the next active player. When everyone has had a turn, the cards are dealt."""
    global current, deadline
    while len(queue) > 0:
        ss = queue.pop(0)
        if ss in players and players[ss]["status"] == "active":
            current = ss
            deadline = time.time() + TURN_TIME
            send(ss, "YOUR_BET " + str(MIN_BET) + " " + str(TURN_TIME))
            broadcast("MSG Waiting for " + players[ss]["name"] + " to bet", ss)
            return
    current = None
    if len(in_round()) > 0:
        deal()
    else:
        broadcast("MSG Nobody bet this round")
        start_round()


def deal():
    global state, deck, dealer
    state = "PLAYING"
    deck = new_deck()
    dealer = [draw(), draw()]
    broadcast("DEALER " + dealer[0] + ",?? ?")
    queue[:] = []
    for ss in in_round():
        p = players[ss]
        p["hand"] = [draw(), draw()]
        send(ss, "HAND " + ",".join(p["hand"]) + " " + str(hand_value(p["hand"])))
        broadcast("MSG " + p["name"] + " has " + show(p["hand"]), ss)
        if hand_value(p["hand"]) == 21:
            send(ss, "DONE")
        else:
            queue.append(ss)
    next_player()


def next_player():
    """Gives the hit/stand turn to the next player. When nobody is left, the dealer plays."""
    global current, deadline
    while len(queue) > 0:
        ss = queue.pop(0)
        if ss in players:
            current = ss
            deadline = time.time() + TURN_TIME
            send(ss, "TURN " + str(TURN_TIME))
            broadcast("MSG " + players[ss]["name"] + "'s turn", ss)
            return
    current = None
    finish_round()


def end_turn(ss, text):
    global current
    send(ss, "DONE")
    broadcast("MSG " + players[ss]["name"] + " " + text + " with " + show(players[ss]["hand"]), ss)
    current = None
    next_player()


def finish_round():
    """The dealer draws to 17, every bet is paid out, and broke players become spectators."""
    while hand_value(dealer) < 17:
        dealer.append(draw())
    dv = hand_value(dealer)
    broadcast("DEALER " + ",".join(dealer) + " " + str(dv))
    dealer_bj = len(dealer) == 2 and dv == 21
    for ss in in_round():
        p = players[ss]
        pv = hand_value(p["hand"])
        player_bj = len(p["hand"]) == 2 and pv == 21
        bet = p["bet"]
        if pv > 21:
            result = "LOSE"
        elif player_bj and not dealer_bj:
            result = "BLACKJACK"
            p["bankroll"] += bet + bet * 3 // 2
        elif dealer_bj and not player_bj:
            result = "LOSE"
        elif dv > 21 or pv > dv:
            result = "WIN"
            p["bankroll"] += 2 * bet
        elif pv == dv:
            result = "PUSH"
            p["bankroll"] += bet
        else:
            result = "LOSE"
        send(ss, "RESULT " + result + " " + str(p["bankroll"]))
        broadcast("MSG " + p["name"] + ": " + result + ", bankroll " + str(p["bankroll"]), ss)
    for ss in active():
        p = players[ss]
        if p["bankroll"] < MIN_BET:
            p["status"] = "spectator"
            send(ss, "SPECTATING")
            broadcast("MSG " + p["name"] + " is bankrupt and now spectates", ss)
        elif p["bankroll"] >= GOAL:
            send(ss, "MSG You have " + str(p["bankroll"]) + "! On your bet turn: SPECTATE to lock in the win, or BET to keep playing")
    pause_results()


def pause_results():
    """Shows the results for a few seconds. No sleep(): only a deadline is set, and the select() timeout wakes the loop."""
    global state, deadline
    state = "RESULTS"
    deadline = time.time() + RESULT_PAUSE


def broadcast_table():
    """Sends everyone a snapshot of the whole table (TABLE + one SEAT per player), which the GUI clients draw."""
    if current is not None and current in players:
        cur = players[current]["name"]
        total = TURN_TIME
    else:
        cur = "-"
        total = RESULT_PAUSE if state == "RESULTS" else 0
    left = max(0, deadline - time.time()) if (cur != "-" or state == "RESULTS") else 0
    lines = ["TABLE " + state + " " + cur + " " + str(round(left, 1)) + " " + str(total)]
    for ss in seated():
        p = players[ss]
        cards = ",".join(p["hand"]) if p["hand"] else "-"
        value = str(hand_value(p["hand"])) if p["hand"] else "-"
        lines.append("SEAT " + p["name"] + " " + str(p["bankroll"]) + " " + str(p["bet"]) + " " + cards + " " + value + " " + p["status"].upper())
    for ss in seated():
        for line in lines:
            send(ss, line)


def game_over():
    """Announces the winners (or the dealer), disconnects everyone and waits for a new match."""
    global state, current
    if len(winners) > 0:
        broadcast("GAME_OVER " + ",".join(winners))
    else:
        broadcast("GAME_OVER DEALER")
    print("Game over. Winners:", winners if winners else "DEALER")
    for ss in list(players):
        ins.remove(ss)
        ss.close()
    players.clear()
    buffers.clear()
    winners.clear()
    state = "WAITING"
    current = None
    queue[:] = []


def turn_timeout():
    """A player let their 20 seconds run out: no bet means sitting out the round, no move means standing."""
    global current
    ss = current
    if state == "BETTING":
        send(ss, "MSG Time is up, you sit out this round")
        broadcast("MSG " + players[ss]["name"] + " sits out this round", ss)
        current = None
        next_bettor()
    else:
        send(ss, "MSG Time is up, you stand")
        end_turn(ss, "stands (timeout)")


def handle_line(ss, line):
    """Handles one complete line from a client: NAME first, then BET, SPECTATE, HIT or STAND on their turn."""
    global deadline
    p = players[ss]
    parts = line.split(" ", 1)
    cmd = parts[0].upper()
    arg = parts[1].strip() if len(parts) > 1 else ""

    if p["name"] is None:
        if cmd == "NAME" and arg != "":
            p["name"] = unique_name(arg)
            send(ss, "WELCOME " + str(p["bankroll"]) + " " + p["name"])
            broadcast("MSG " + p["name"] + " joined the table", ss)
            if state == "WAITING":
                start_round()
            else:
                send(ss, "MSG Round in progress, you play from the next round")
        else:
            send(ss, "ERROR Send NAME <name> first")
        return

    if ss != current:
        send(ss, "ERROR Not your turn")
        return

    if state == "BETTING" and cmd == "BET":
        if not arg.isdecimal():   # isdigit() would accept "²", which int() rejects
            send(ss, "ERROR Bet must be a number")
        elif int(arg) < MIN_BET or int(arg) > p["bankroll"]:
            send(ss, "ERROR Bet must be between " + str(MIN_BET) + " and " + str(p["bankroll"]))
        elif int(arg) % 10 != 0:
            send(ss, "ERROR Bet must be a multiple of 10")
        else:
            p["bet"] = int(arg)
            p["bankroll"] -= p["bet"]
            send(ss, "BANKROLL " + str(p["bankroll"]))
            broadcast("MSG " + p["name"] + " bets " + arg, ss)
            next_bettor()
    elif state == "BETTING" and cmd == "SPECTATE":
        if p["bankroll"] < GOAL:
            send(ss, "ERROR You need " + str(GOAL) + " to spectate as a winner")
        else:
            p["status"] = "spectator"
            winners.append(p["name"])
            send(ss, "SPECTATING")
            broadcast("MSG " + p["name"] + " locks in the win with " + str(p["bankroll"]) + " and spectates", ss)
            next_bettor()
    elif state == "PLAYING" and cmd == "HIT":
        p["hand"].append(draw())
        v = hand_value(p["hand"])
        send(ss, "HAND " + ",".join(p["hand"]) + " " + str(v))
        if v > 21:
            end_turn(ss, "busts")
        elif v == 21:
            end_turn(ss, "stands")
        else:
            deadline = time.time() + TURN_TIME
            send(ss, "TURN " + str(TURN_TIME))
    elif state == "PLAYING" and cmd == "STAND":
        end_turn(ss, "stands")
    else:
        send(ss, "ERROR You can't do that now")


print("Blackjack server listening on port", PORT)
print("  this PC:           127.0.0.1")
print("  phone / other PCs:", lan_ip())

# The whole server is this loop, in one thread. select() sleeps until a socket needs
# attention (a new client, data, a disconnect) or until the current deadline passes.
while ins:
    timeout = None
    if current is not None or state == "RESULTS":
        timeout = max(0, deadline - time.time())
    r, w, e = select.select(ins, [], ins, timeout)
    for ss in r:
        if ss not in ins:
            continue  # removed earlier in this same loop
        if ss == s:
            cs, addr = ss.accept()
            if len(players) >= MAX_PLAYERS:
                send(cs, "ERROR Table is full")
                cs.close()
                continue
            cs.settimeout(SEND_TIMEOUT)
            print("Client from:", addr)
            ins.append(cs)
            buffers[cs] = b""
            players[cs] = {"name": None, "bankroll": START_BANKROLL, "bet": 0, "hand": [], "status": "active"}
        else:
            try:
                data = ss.recv(1024)
            except OSError:
                data = b""
            if not data:
                remove_client(ss)
                continue
            buffers[ss] += data
            while ss in ins and b"\n" in buffers[ss]:
                line, buffers[ss] = buffers[ss].split(b"\n", 1)
                line = line.decode("utf-8", errors="replace").strip()
                print("From", players[ss]["name"], ":", line)
                if line != "":
                    handle_line(ss, line)
    for ss in e:
        if ss in ins and ss != s:
            print("Error")
            remove_client(ss)
    if current is not None and time.time() >= deadline:
        turn_timeout()
    elif state == "RESULTS" and time.time() >= deadline:
        start_round()
    broadcast_table()
