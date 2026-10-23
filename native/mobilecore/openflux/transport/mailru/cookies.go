package mailru

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

// loadMailruCookies imports a Netscape cookies.txt export into an HTTP cookie
// jar. Accepts mail.ru, cloud.mail.ru, datacloudmail.ru and their subdomains;
// cookie values are never included in error messages.
func loadMailruCookies(path string, jar http.CookieJar) error {
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open Mail.ru cookie file: %w", err)
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	loaded := 0
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
				setMailruCookieOnAllHosts(jar, "mail.ru", "/", name, val, true)
				loaded++
			}
			continue
		}
		rawDomain := fields[0]
		if strings.HasPrefix(strings.ToLower(rawDomain), "#httponly_") {
			rawDomain = rawDomain[len("#httponly_"):]
		}
		domain := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(rawDomain), "."))
		if domain != "mail.ru" && !strings.HasSuffix(domain, ".mail.ru") &&
			domain != "datacloudmail.ru" && !strings.HasSuffix(domain, ".datacloudmail.ru") {
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
		expires, _ := strconv.ParseInt(strings.TrimSpace(fields[4]), 10, 64)
		_ = time.Now()
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
		setMailruCookieOnAllHosts(jar, domain, cPath, name, val, secFlag == "TRUE")
		loaded++
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("read Mail.ru cookie file: %w", err)
	}
	if loaded == 0 {
		return fmt.Errorf("Mail.ru cookie file has no mail.ru cookies")
	}
	return nil
}

func setMailruCookieOnAllHosts(jar http.CookieJar, domain, cPath, name, value string, secure bool) {
	baseCookie := &http.Cookie{
		Name:   name,
		Value:  value,
		Path:   "/",
		Domain: "mail.ru",
		Secure: secure,
	}
	jar.SetCookies(&url.URL{Scheme: "https", Host: "mail.ru", Path: "/"}, []*http.Cookie{baseCookie})
	hosts := []string{"cloud.mail.ru", "doc.mail.ru", "docs.mail.ru"}
	if domain != "" && domain != "mail.ru" {
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
