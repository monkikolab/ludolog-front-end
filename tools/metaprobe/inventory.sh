#!/usr/bin/env bash
# Recolecta, por cada juego del indice de Ludolog, lo que el propio archivo dice de si mismo:
# tamano, huellas (md5/sha1) de los pequenos, CRC de lo que hay dentro de los zip y volcados de
# cabecera. Todo en solo lectura; nada se copia salvo los .n64 (hay que darles la vuelta).
set -u
ADB="${ADB:-adb}${SERIAL:+ -s $SERIAL}"   # sin SERIAL, adb usa el unico aparato conectado
OUT="inventory.txt"
: > "$OUT"
export MSYS_NO_PATHCONV=1

sh_q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

tail -n +2 library.idx | tr -d '\r' | while IFS= read -r path; do
  [ -z "$path" ] && continue
  q=$(sh_q "$path")
  ext=$(printf '%s' "${path##*.}" | tr 'A-Z' 'a-z')
  size=$($ADB shell "stat -c %s $q" < /dev/null | tr -d '\r')
  {
    printf 'FILE\t%s\n' "$path"
    printf 'SIZE\t%s\n' "$size"
    case "$ext" in
      sfc|smc|gba|gb|gbc|nes|md|smd|gen|sms|gg|pce|32x|a26|lnx|ngp|ngc|ws|wsc|vb)
        $ADB shell "md5sum $q; sha1sum $q" < /dev/null | tr -d '\r' | awk '{print (length($1)==32?"MD5":"SHA1") "\t" $1}'
        ;;
    esac
    case "$ext" in
      nes)
        $ADB shell "tail -c +17 $q | md5sum; tail -c +17 $q | sha1sum" < /dev/null | tr -d '\r' | awk '{print (length($1)==32?"MD5NOHDR":"SHA1NOHDR") "\t" $1}'
        printf 'HEX\t0\t%s\n' "$($ADB shell "xxd -p -l 16 $q" < /dev/null | tr -d '\r\n')"
        ;;
      sfc|smc)
        for off in 32688 65456 33200 65968; do
          printf 'HEX\t%s\t%s\n' "$off" "$($ADB shell "xxd -p -s $off -l 80 $q" < /dev/null | tr -d '\r\n')"
        done
        ;;
      gba)
        printf 'HEX\t160\t%s\n' "$($ADB shell "xxd -p -s 160 -l 32 $q" < /dev/null | tr -d '\r\n')"
        ;;
      zip)
        $ADB shell "unzip -lv $q" < /dev/null | tr -d '\r' | awk 'NR>3 && $7 ~ /^[0-9a-f]{8}$/ { n=$8; for(i=9;i<=NF;i++) n=n" "$i; print "ZIP\t" $7 "\t" $1 "\t" n }'
        ;;
      chd)
        printf 'HEX\t0\t%s\n' "$($ADB shell "xxd -p -l 124 $q" < /dev/null | tr -d '\r\n')"
        ;;
      rvz|iso|bin|xci)
        printf 'HEX\t0\t%s\n' "$($ADB shell "xxd -p -l 256 $q" < /dev/null | tr -d '\r\n')"
        ;;
      steam)
        printf 'TEXT\t%s\n' "$($ADB shell "head -c 200 $q" < /dev/null | tr -d '\r' | tr '\n' ' ')"
        ;;
      n64|z64|v64)
        mkdir -p pulled
        $ADB pull "$path" "pulled/$(basename "$path")" > /dev/null 2>&1 < /dev/null && printf 'PULLED\tpulled/%s\n' "$(basename "$path")"
        ;;
    esac
  } >> "$OUT"
done
echo "done: $(grep -c '^FILE' "$OUT") files"
