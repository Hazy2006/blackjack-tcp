#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <netinet/in.h>
#include <arpa/inet.h>

int s;
pid_t child;      /* the process that reads from the server */
char buf[4096];   /* bytes received from the server, not yet split into lines */
int buflen = 0;
int bankroll = 0; /* last bankroll the server told us */

void send_line(const char *line) {
    char out[256];
    int n = snprintf(out, sizeof(out), "%s\n", line);
    send(s, out, n, 0);
}

void print_cards(const char *cards) {
    for (const char *c = cards; *c; c++)
        putchar(*c == ',' ? ' ' : *c);
}

void handle_server_line(char *line) {
    char cmd[32] = "", a[256] = "", b[64] = "";
    sscanf(line, "%31s %255s %63s", cmd, a, b);
    char *rest = strchr(line, ' ');
    rest = rest ? rest + 1 : "";

    if (strcmp(cmd, "WELCOME") == 0)        { bankroll = atoi(a); printf("Welcome, %s! Bankroll: %s\n", b, a); }
    else if (strcmp(cmd, "YOUR_BET") == 0) {
        printf(">>> Your bet (min %s), %s seconds: bet <amount>\n", a, b);
        if (bankroll >= 20000) printf(">>> You have %d! Type spectate to lock in the win\n", bankroll);
    }
    else if (strcmp(cmd, "BANKROLL") == 0)  { bankroll = atoi(a); printf("Bet accepted. Bankroll: %s\n", a); }
    else if (strcmp(cmd, "HAND") == 0)      { printf("Your hand: "); print_cards(a); printf("  (%s)\n", b); }
    else if (strcmp(cmd, "DEALER") == 0)    { printf("Dealer:    "); print_cards(a); printf("  (%s)\n", b); }
    else if (strcmp(cmd, "TURN") == 0)      printf(">>> Your move, %s seconds: hit or stand\n", a);
    else if (strcmp(cmd, "DONE") == 0)      printf("Your turn is over.\n");
    else if (strcmp(cmd, "SPECTATING") == 0) printf("You are now a spectator.\n");
    else if (strcmp(cmd, "GAME_OVER") == 0) {
        if (strcmp(a, "DEALER") == 0) printf("=== GAME OVER: the dealer wins ===\n");
        else                          printf("=== GAME OVER: winners: %s ===\n", a);
    }
    else if (strcmp(cmd, "RESULT") == 0)    { bankroll = atoi(b); printf("Result: %s   Bankroll: %s\n", a, b); }
    else if (strcmp(cmd, "MSG") == 0)       printf("* %s\n", rest);
    else if (strcmp(cmd, "ERROR") == 0)     printf("Error: %s\n", rest);
    else if (strcmp(cmd, "TABLE") == 0 || strcmp(cmd, "SEAT") == 0) return; /* table snapshot, only for GUI clients */
    else                                    printf("%s\n", line);
    fflush(stdout);
}

void handle_user_line(char *line) {
    char cmd[32] = "", arg[64] = "", out[128];
    sscanf(line, "%31s %63s", cmd, arg);
    if (strcmp(cmd, "bet") == 0 || strcmp(cmd, "b") == 0) {
        snprintf(out, sizeof(out), "BET %s", arg);
        send_line(out);
    } else if (strcmp(cmd, "hit") == 0 || strcmp(cmd, "h") == 0) {
        send_line("HIT");
    } else if (strcmp(cmd, "stand") == 0 || strcmp(cmd, "s") == 0) {
        send_line("STAND");
    } else if (strcmp(cmd, "spectate") == 0) {
        send_line("SPECTATE");
    } else if (strcmp(cmd, "quit") == 0 || strcmp(cmd, "q") == 0) {
        kill(child, SIGTERM);
        waitpid(child, NULL, 0);   /* collect it, so it doesn't stay a zombie */
        close(s);
        exit(0);
    } else if (cmd[0] != 0) {
        printf("Commands: bet <amount> | hit | stand | spectate | quit\n");
    }
}

int main(int argc, char *argv[]) {
    if (argc != 4) {
        printf("Usage: %s <server_ip> <port> <name>\n", argv[0]);
        return 1;
    }

    struct sockaddr_in soc;
    s = socket(AF_INET, SOCK_STREAM, 0);
    soc.sin_family = AF_INET;
    soc.sin_port = htons(atoi(argv[2]));
    soc.sin_addr.s_addr = inet_addr(argv[1]);
    if (connect(s, (struct sockaddr *)&soc, sizeof(soc)) < 0) {
        perror("connect");
        return 1;
    }

    char out[128];
    snprintf(out, sizeof(out), "NAME %s", argv[3]);
    send_line(out);

    /* Like the teacher's chat client: two processes share the socket.
       The child only reads from the server, the parent only reads the keyboard. */
    child = fork();
    if (child < 0) {
        perror("fork");
        return 1;
    }

    if (child == 0) {
        while (1) {
            int r = recv(s, buf + buflen, sizeof(buf) - buflen - 1, 0);
            if (r <= 0) {
                printf("Server closed the connection.\n");
                kill(getppid(), SIGTERM);   /* the parent is blocked in fgets, stop it too */
                exit(0);
            }
            buflen += r;
            char *nl;
            while ((nl = memchr(buf, '\n', buflen)) != NULL) {   /* TCP is a stream: split on \n */
                *nl = 0;
                if (nl > buf && nl[-1] == '\r') nl[-1] = 0;
                handle_server_line(buf);
                int used = nl - buf + 1;
                memmove(buf, nl + 1, buflen - used);
                buflen -= used;
            }
        }
    }

    char line[128];
    while (fgets(line, sizeof(line), stdin) != NULL) handle_user_line(line);
    kill(child, SIGTERM);   /* keyboard closed (Ctrl+D): stop the reader too */
    waitpid(child, NULL, 0);
    close(s);
    return 0;
}