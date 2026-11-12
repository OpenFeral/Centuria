#!/bin/bash
chmod +x "$0"
dirpath="$(dirname "$0")"

plat="$(uname -s | tr '[:upper:]' '[:lower:]')"
if [ "${plat//_*/}" == "cygwin" ] || [ "${plat//_*/}" == "mingw64" ]; then
	plat="${plat//_*/}"
fi
sep=":"
if [ "$plat" == "cygwin" ] || [ "$plat" == "mingw64" ] ; then
    # Msys support
    sep=";"
fi

libs=$(find "$dirpath/libs/" -name '*.jar' -exec echo -n "$sep{}" \;)
libs=${libs:1}

java -cp "$libs" "$@"
exit $?
