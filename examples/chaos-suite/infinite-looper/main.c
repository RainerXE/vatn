#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>

int main() {
    int port = atoi(getenv("VATN_IPC_PORT") ? getenv("VATN_IPC_PORT") : "8080");
    int s = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in addr = { .sin_family = AF_INET, .sin_port = htons(port), .sin_addr.s_addr = INADDR_ANY };
    bind(s, (struct sockaddr *)&addr, sizeof(addr));
    listen(s, 1);
    
    int check_count = 0;
    printf("[CHAOS-HANG] Starting infinite looper on port %d...\n", port);

    while(1) {
        int c = accept(s, NULL, NULL);
        char buf[1024] = {0};
        read(c, buf, 1024);
        
        if (strstr(buf, "STATUS_CHECK")) {
            check_count++;
            if (check_count > 2) {
                printf("[CHAOS-HANG] Triggering infinite loop now! Watchdog should catch this.\n");
                while(1) { /* HANG */ }
            }
            char *res = "{\"status\": \"HEALTHY\"}";
            send(c, res, strlen(res), 0);
        }
        close(c);
    }
    return 0;
}
