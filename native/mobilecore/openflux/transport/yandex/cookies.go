package yandex

import (
	"bufio"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// loadYandexCookies imports a Netscape cookies.txt export into an HTTP cookie
// jar. Only yandex.ru and its subdomains are accepted; cookie values are never
// included in error messages.
func loadYandexCookies(path string, jar http.CookieJar) error {
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open Yandex cookie file: %w", err)
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	loaded := 0
	now := time.Now().Unix()
	for lineNo := 1; scanner.Scan(); lineNo++ {
		line := strings.TrimSpace(strings.TrimSuffix(scanner.Text(), "\r"))
		if line == "" {
			continue
		}
		lowerLine := strings.ToLower(line)
		if strings.HasPrefix(lowerLine, "#httponly_") {
			line = line[len("#httponly_"):]
		} else if strings.HasPrefix(line, "#") {
			continue
		}

		fields := strings.Split(line, "\t")
		if len(fields) < 7 {
			rawLine := line
			if strings.HasPrefix(strings.ToLower(rawLine), "cookie:") {
				rawLine = strings.TrimSpace(rawLine[len("cookie:"):])
			}
			for _, part := range strings.Split(rawLine, ";") {
				part = strings.TrimSpace(part)
				eq := strings.IndexByte(part, '=')
				if eq <= 0 {
					continue
				}
				name, val := strings.TrimSpace(part[:eq]), strings.TrimSpace(part[eq+1:])
				if name == "" || val == "" {
					continue
				}
				setYandexCookieOnAllHosts(jar, "yandex.ru", "/", name, val, true)
				loaded++
			}
			continue
		}
		rawDomain := fields[0]
		if strings.HasPrefix(strings.ToLower(rawDomain), "#httponly_") {
			rawDomain = rawDomain[len("#httponly_"):]
		}
		domain := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(rawDomain), "."))
		if domain != "yandex.ru" && !strings.HasSuffix(domain, ".yandex.ru") {
			continue
		}
		subFlag := strings.ToUpper(strings.TrimSpace(fields[1]))
		if subFlag != "TRUE" && subFlag != "FALSE" {
			continue
		}
		secFlag := strings.ToUpper(strings.TrimSpace(fields[3]))
		if secFlag != "TRUE" && secFlag != "FALSE" {
			continue
		}
		expires, err := strconv.ParseInt(strings.TrimSpace(fields[4]), 10, 64)
		if err != nil {
			expires = 0
		}
		_ = now
		_ = expires
		name := strings.TrimSpace(fields[5])
		val := strings.TrimSpace(fields[6])
		if name == "" {
			continue
		}
		cPath := strings.TrimSpace(fields[2])
		if !strings.HasPrefix(cPath, "/") {
			cPath = "/"
		}
		setYandexCookieOnAllHosts(jar, domain, cPath, name, val, secFlag == "TRUE")
		loaded++
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("read Yandex cookie file: %w", err)
	}
	if loaded == 0 {
		return fmt.Errorf("Yandex cookie file has no yandex.ru cookies")
	}
	return nil
}

func setYandexCookieOnAllHosts(jar http.CookieJar, domain, cPath, name, value string, secure bool) {
	baseCookie := &http.Cookie{
		Name:   name,
		Value:  value,
		Path:   "/",
		Domain: "yandex.ru",
		Secure: secure,
	}
	jar.SetCookies(&url.URL{Scheme: "https", Host: "yandex.ru", Path: "/"}, []*http.Cookie{baseCookie})
	hosts := []string{"docs.yandex.ru", "volga.yandex.ru", "push.yandex.ru", "disk.yandex.ru", "passport.yandex.ru"}
	if domain != "" && domain != "yandex.ru" {
		hosts = append(hosts, domain)
	}
	for _, h := range hosts {
		hc := &http.Cookie{
			Name:   name,
			Value:  value,
			Path:   cPath,
			Domain: h,
			Secure: secure,
		}
		jar.SetCookies(&url.URL{Scheme: "https", Host: h, Path: cPath}, []*http.Cookie{hc})
	}
}
