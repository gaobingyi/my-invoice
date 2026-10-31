// Package webstatic 可选静态文件服务：Vue dist 的 SPA 兜底 + 缓存语义
//（对齐 web/nginx.conf：/assets/ immutable 一年长缓存，index.html 与其余 no-cache）。
package webstatic

import (
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
)

type handler struct {
	dir string
}

func New(dir string) http.Handler { return handler{dir: dir} }

func (h handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	upath := path.Clean("/" + r.URL.Path) // 防穿越：Clean 后必在根内
	if strings.HasPrefix(upath, "/assets/") {
		full := filepath.Join(h.dir, filepath.FromSlash(upath))
		if fi, err := os.Stat(full); err == nil && !fi.IsDir() {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
			http.ServeFile(w, r, full)
			return
		}
		http.NotFound(w, r)
		return
	}
	// 其余：命中真实文件则直出，否则 SPA fallback → index.html
	full := filepath.Join(h.dir, filepath.FromSlash(upath))
	if fi, err := os.Stat(full); err == nil && !fi.IsDir() {
		w.Header().Set("Cache-Control", "no-cache")
		http.ServeFile(w, r, full)
		return
	}
	w.Header().Set("Cache-Control", "no-cache")
	http.ServeFile(w, r, filepath.Join(h.dir, "index.html"))
}
