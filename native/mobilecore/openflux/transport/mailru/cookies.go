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
		line := strings.TrimSuffix(scanner.Text(), "\r")
		if line == "" || (strings.HasPrefix(line, "#") && !strings.HasPrefix(line, "#HttpOnly_")) {
			continue
		}

		fields := strings.Split(line, "\t")
		if len(fields) != 7 {
			return fmt.Errorf("Mail.ru cookie file line %d: expected 7 tab-separated fields", lineNo)
		}
		rawDomain := strings.TrimPrefix(fields[0], "#HttpOnly_")
		domain := strings.ToLower(strings.TrimPrefix(rawDomain, "."))
		if domain != "mail.ru" && !strings.HasSuffix(domain, ".mail.ru") &&
			domain != "datacloudmail.ru" && !strings.HasSuffix(domain, ".datacloudmail.ru") {
			continue
		}
		if fields[1] != "TRUE" && fields[1] != "FALSE" {
			return fmt.Errorf("Mail.ru cookie file line %d: invalid subdomain flag", lineNo)
		}
		if fields[3] != "TRUE" && fields[3] != "FALSE" {
			return fmt.Errorf("Mail.ru cookie file line %d: invalid secure flag", lineNo)
		}
		expires, err := strconv.ParseInt(fields[4], 10, 64)
		if err != nil {
			return fmt.Errorf("Mail.ru cookie file line %d: invalid expiry", lineNo)
		}
		if expires != 0 && expires <= time.Now().Unix() {
			continue
		}
		if fields[5] == "" {
			return fmt.Errorf("Mail.ru cookie file line %d: empty cookie name", lineNo)
		}
		cPath := fields[2]
		if !strings.HasPrefix(cPath, "/") {
			return fmt.Errorf("Mail.ru cookie file line %d: invalid cookie path", lineNo)
		}
		cookie := &http.Cookie{
			Name:   fields[5],
			Value:  fields[6],
			Path:   cPath,
			Secure: fields[3] == "TRUE",
		}
		if fields[1] == "TRUE" {
			cookie.Domain = domain
		}
		if expires != 0 {
			cookie.Expires = time.Unix(expires, 0)
		}
		jar.SetCookies(&url.URL{Scheme: "https", Host: domain, Path: cPath}, []*http.Cookie{cookie})
		if strings.HasSuffix(domain, "mail.ru") {
			jar.SetCookies(&url.URL{Scheme: "https", Host: "cloud.mail.ru", Path: cPath}, []*http.Cookie{cookie})
		}
		loaded++
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("read Mail.ru cookie file: %w", err)
	}
	if loaded == 0 {
		return fmt.Errorf("Mail.ru cookie file has no unexpired mail.ru cookies")
	}
	return nil
}
