package openflux

import (
	"github.com/openlibrecommunity/olcrtc/mobilecore/openflux/utils"
)

func EnableDebug() {
	utils.EnableDebug()
}

func Debugf(format string, args ...interface{}) {
	utils.Debugf(format, args...)
}

func Infof(format string, args ...interface{}) {
	utils.Infof(format, args...)
}

func SafeGo(name string, fn func()) {
	utils.SafeGo(name, fn)
}

func SetLogSink(sink func(string)) {
	utils.SetLogSink(sink)
}

func IsVerbose() bool {
	return utils.Level() > 0
}
