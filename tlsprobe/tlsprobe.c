/*
 * tlsprobe - mimics the Symbian EKA1 TLS patch (shinovon/symbian-tls, ssladaptor.dll):
 * BearSSL from shinovon/bearssl-symbian, br_ssl_client_init_full with no trust anchors,
 * certificate errors ignored exactly like CMbedContext (x509 end_chain returns 0),
 * and - like the phone's Java HttpsConnection - NO SNI unless "-sni" is given.
 *
 * usage: tlsprobe [-sni] [-path /robots.txt] host[/path] ...
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#else
#include <sys/socket.h>
#include <netdb.h>
#include <unistd.h>
#include <time.h>
typedef int SOCKET;
typedef unsigned int DWORD;
#define closesocket close
static DWORD GetTickCount(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (DWORD)(t.tv_sec * 1000 + t.tv_nsec / 1000000); }
#endif
#include "bearssl.h"

const char *find_error_name(int err, const char **comment);

static int sock_read(void *ctx, unsigned char *buf, size_t len) {
	int r = recv(*(SOCKET *)ctx, (char *)buf, (int)len, 0);
	return r <= 0 ? -1 : r;
}
static int sock_write(void *ctx, const unsigned char *buf, size_t len) {
	int r = send(*(SOCKET *)ctx, (const char *)buf, (int)len, 0);
	return r <= 0 ? -1 : r;
}

/* same certificate "verifier" as the patch, plus chain statistics */
static int n_certs; static unsigned long chain_bytes;
static void x_start_chain(const br_x509_class **c, const char *sn) { n_certs = 0; chain_bytes = 0; br_x509_minimal_vtable.start_chain(c, sn); }
static void x_start_cert(const br_x509_class **c, uint32_t len) { n_certs++; chain_bytes += len; br_x509_minimal_vtable.start_cert(c, len); }
static void x_append(const br_x509_class **c, const unsigned char *b, size_t l) { br_x509_minimal_vtable.append(c, b, l); }
static void x_end_cert(const br_x509_class **c) { br_x509_minimal_vtable.end_cert(c); }
static unsigned x_end_chain(const br_x509_class **c) { (void)br_x509_minimal_vtable.end_chain(c); return 0; }
static const br_x509_pkey *x_get_pkey(const br_x509_class *const *c, unsigned *u) { return br_x509_minimal_vtable.get_pkey(c, u); }
static int time_ok(void *ctx, uint32_t a, uint32_t b, uint32_t c, uint32_t d) { return 0; }

static const char *suite_name(unsigned s) {
	switch (s) {
	case 0xC02B: return "ECDHE-ECDSA-AES128-GCM-SHA256";
	case 0xC02C: return "ECDHE-ECDSA-AES256-GCM-SHA384";
	case 0xC02F: return "ECDHE-RSA-AES128-GCM-SHA256";
	case 0xC030: return "ECDHE-RSA-AES256-GCM-SHA384";
	case 0xCCA8: return "ECDHE-RSA-CHACHA20-POLY1305";
	case 0xCCA9: return "ECDHE-ECDSA-CHACHA20-POLY1305";
	case 0xC009: return "ECDHE-ECDSA-AES128-SHA";
	case 0xC013: return "ECDHE-RSA-AES128-SHA";
	case 0xC014: return "ECDHE-RSA-AES256-SHA";
	case 0x009C: return "RSA-AES128-GCM-SHA256";
	case 0x002F: return "RSA-AES128-SHA";
	case 0x0035: return "RSA-AES256-SHA";
	}
	return "?";
}

static void probe(const char *arg, int use_sni) {
	char host[256], path[512];
	const char *slash = strchr(arg, '/');
	size_t hl = slash ? (size_t)(slash - arg) : strlen(arg);
	if (hl >= sizeof host) hl = sizeof host - 1;
	memcpy(host, arg, hl); host[hl] = 0;
	snprintf(path, sizeof path, "%s", slash ? slash : "/");

	printf("%-30s %-6s ", host, use_sni ? "SNI" : "no-SNI");
	fflush(stdout);

	struct addrinfo hints, *ai = NULL;
	memset(&hints, 0, sizeof hints);
	hints.ai_family = AF_INET; hints.ai_socktype = SOCK_STREAM;
	if (getaddrinfo(host, getenv("TLSPROBE_PORT") ? getenv("TLSPROBE_PORT") : "443", &hints, &ai) != 0) { printf("DNS failed\n"); return; }
	SOCKET fd = socket(AF_INET, SOCK_STREAM, 0);
#ifdef _WIN32
	DWORD to = 20000;
	setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, (const char *)&to, sizeof to);
#else
	struct timeval to = { 20, 0 };
	setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &to, sizeof to);
#endif
	if (connect(fd, ai->ai_addr, (int)ai->ai_addrlen) != 0) { printf("connect failed\n"); freeaddrinfo(ai); closesocket(fd); return; }
	freeaddrinfo(ai);

	static br_ssl_client_context sc;
	static br_x509_minimal_context xc;
	static unsigned char iobuf[BR_SSL_BUFSIZE_BIDI];
	static br_x509_class vt;
	br_sslio_context ioc;

	br_ssl_client_init_full(&sc, &xc, NULL, 0);
	br_x509_minimal_set_time_callback(&xc, NULL, time_ok);
	vt.context_size = sizeof(br_x509_minimal_context);
	vt.start_chain = x_start_chain; vt.start_cert = x_start_cert; vt.append = x_append;
	vt.end_cert = x_end_cert; vt.end_chain = x_end_chain; vt.get_pkey = x_get_pkey;
	br_ssl_engine_set_buffer(&sc.eng, iobuf, sizeof iobuf, 1);
	br_ssl_client_reset(&sc, use_sni ? host : NULL, 0);
	xc.vtable = &vt;
	br_sslio_init(&ioc, &sc.eng, sock_read, &fd, sock_write, &fd);

	char req[1024];
	snprintf(req, sizeof req,
		"GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: Nokia9300/tlsprobe\r\nConnection: close\r\n\r\n", path, host);
	DWORD t0 = GetTickCount();
	int ok = br_sslio_write_all(&ioc, req, strlen(req)) == 0 && br_sslio_flush(&ioc) == 0;
	char resp[4096]; int got = 0;
	if (ok) {
		for (;;) {
			int r = br_sslio_read(&ioc, resp + got, sizeof resp - 1 - got);
			if (r <= 0) break;
			got += r;
			if (memchr(resp, '\n', got) || got >= (int)sizeof resp - 1) break;
		}
	}
	DWORD ms = GetTickCount() - t0;
	resp[got] = 0;
	char *nl = strpbrk(resp, "\r\n"); if (nl) *nl = 0;

	int err = br_ssl_engine_last_error(&sc.eng);
	unsigned v = sc.eng.session.version, cs = sc.eng.session.cipher_suite;
	if (got > 0) printf("%-28s", resp);
	else printf("%-28s", "NO RESPONSE");
	printf(" %5lu ms", (unsigned long)ms);
	if (cs) printf("  TLS%s %s", v == 0x0303 ? "1.2" : v == 0x0302 ? "1.1" : v == 0x0301 ? "1.0" : "?", suite_name(cs));
	if (n_certs) printf("  chain %d certs/%lu B", n_certs, chain_bytes);
	if (err != BR_ERR_OK) {
		const char *comment = NULL;
		const char *name = find_error_name(err, &comment);
		if (err >= BR_ERR_RECV_FATAL_ALERT && err < BR_ERR_RECV_FATAL_ALERT + 256)
			printf("  ERR server sent fatal alert %d", err - BR_ERR_RECV_FATAL_ALERT);
		else if (err >= BR_ERR_SEND_FATAL_ALERT && err < BR_ERR_SEND_FATAL_ALERT + 256)
			printf("  ERR we sent fatal alert %d", err - BR_ERR_SEND_FATAL_ALERT);
		else
			printf("  ERR %d %s", err, name ? name : "");
	}
	printf("\n");
	closesocket(fd);
}

int main(int argc, char **argv) {
	static const char *defaults[] = {
		"www.google.com/generate_204", "jsonplaceholder.typicode.com/posts/1",
		"www.seznam.cz/robots.txt", "lite.duckduckgo.com/robots.txt", "lite.cnn.com/robots.txt",
		"text.npr.org/robots.txt", "example.com/", "pubtran-backend.mapy.cz/api/v1/", NULL };
#ifdef _WIN32
	WSADATA w; WSAStartup(MAKEWORD(2, 2), &w);
#endif
	int mode = 0; /* 0 = both, 1 = sni only, 2 = no-sni only */
	int i, n = 0;
	for (i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "-sni")) mode = 1;
		else if (!strcmp(argv[i], "-nosni")) mode = 2;
	}
	for (i = 1; i < argc; i++) {
		if (argv[i][0] == '-') continue;
		if (mode != 1) probe(argv[i], 0);
		if (mode != 2) probe(argv[i], 1);
		n++;
	}
	if (n == 0) for (i = 0; defaults[i]; i++) {
		if (mode != 1) probe(defaults[i], 0);
		if (mode != 2) probe(defaults[i], 1);
	}
	return 0;
}
