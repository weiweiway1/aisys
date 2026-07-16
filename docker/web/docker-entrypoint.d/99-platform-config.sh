#!/bin/sh
set -eu

HTML_DIR="/usr/share/nginx/html"
CONFIG_FILE="${HTML_DIR}/platform-config.json"

json_bool() {
  case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
    true|1|yes|on) printf 'true' ;;
    *) printf 'false' ;;
  esac
}

json_num() {
  case "${1:-}" in
    ''|*[!0-9.-]*)
      printf '%s' "${2:-0}"
      ;;
    *)
      printf '%s' "$1"
      ;;
  esac
}

: "${WEB_VERSION:=7.0.0}"
: "${WEB_TITLE:=Pure}"
: "${WEB_FIXED_HEADER:=true}"
: "${WEB_HIDDEN_SIDE_BAR:=false}"
: "${WEB_MULTI_TAGS_CACHE:=false}"
: "${WEB_KEEP_ALIVE:=true}"
: "${WEB_LOCALE:=zh}"
: "${WEB_LAYOUT:=vertical}"
: "${WEB_THEME:=light}"
: "${WEB_DARK_MODE:=false}"
: "${WEB_THEME_MODE:=light}"
: "${WEB_GREY:=false}"
: "${WEB_WEAK:=false}"
: "${WEB_HIDE_TABS:=false}"
: "${WEB_HIDE_FOOTER:=false}"
: "${WEB_STRETCH:=false}"
: "${WEB_SIDEBAR_STATUS:=true}"
: "${WEB_EP_THEME_COLOR:=#409EFF}"
: "${WEB_SHOW_LOGO:=true}"
: "${WEB_WATERMARK:=false}"
: "${WEB_WATERMARK_TEXT:=}"
: "${WEB_TAGS_STYLE:=chrome}"
: "${WEB_MENU_ARROW_ICON_NO_TRANSITION:=false}"
: "${WEB_CACHING_ASYNC_ROUTES:=false}"
: "${WEB_TOOLTIP_EFFECT:=light}"
: "${WEB_RESPONSIVE_STORAGE_NAME_SPACE:=responsive-}"
: "${WEB_MENU_SEARCH_HISTORY:=6}"
: "${WEB_MAP_AMAP_KEY:=adc139d56406f3844c8f1cf1c6b65c41}"
: "${WEB_MAP_RESIZE_ENABLE:=true}"
: "${WEB_MAP_CENTER_LNG:=113.6401}"
: "${WEB_MAP_CENTER_LAT:=34.72468}"
: "${WEB_MAP_ZOOM:=12}"

mkdir -p "${HTML_DIR}"

jq -n \
  --arg Version "${WEB_VERSION}" \
  --arg Title "${WEB_TITLE}" \
  --argjson FixedHeader "$(json_bool "${WEB_FIXED_HEADER}")" \
  --argjson HiddenSideBar "$(json_bool "${WEB_HIDDEN_SIDE_BAR}")" \
  --argjson MultiTagsCache "$(json_bool "${WEB_MULTI_TAGS_CACHE}")" \
  --argjson KeepAlive "$(json_bool "${WEB_KEEP_ALIVE}")" \
  --arg Locale "${WEB_LOCALE}" \
  --arg Layout "${WEB_LAYOUT}" \
  --arg Theme "${WEB_THEME}" \
  --argjson DarkMode "$(json_bool "${WEB_DARK_MODE}")" \
  --arg ThemeMode "${WEB_THEME_MODE}" \
  --argjson Grey "$(json_bool "${WEB_GREY}")" \
  --argjson Weak "$(json_bool "${WEB_WEAK}")" \
  --argjson HideTabs "$(json_bool "${WEB_HIDE_TABS}")" \
  --argjson HideFooter "$(json_bool "${WEB_HIDE_FOOTER}")" \
  --argjson Stretch "$(json_bool "${WEB_STRETCH}")" \
  --argjson SidebarStatus "$(json_bool "${WEB_SIDEBAR_STATUS}")" \
  --arg EpThemeColor "${WEB_EP_THEME_COLOR}" \
  --argjson ShowLogo "$(json_bool "${WEB_SHOW_LOGO}")" \
  --argjson Watermark "$(json_bool "${WEB_WATERMARK}")" \
  --arg WatermarkText "${WEB_WATERMARK_TEXT}" \
  --arg TagsStyle "${WEB_TAGS_STYLE}" \
  --argjson MenuArrowIconNoTransition "$(json_bool "${WEB_MENU_ARROW_ICON_NO_TRANSITION}")" \
  --argjson CachingAsyncRoutes "$(json_bool "${WEB_CACHING_ASYNC_ROUTES}")" \
  --arg TooltipEffect "${WEB_TOOLTIP_EFFECT}" \
  --arg ResponsiveStorageNameSpace "${WEB_RESPONSIVE_STORAGE_NAME_SPACE}" \
  --argjson MenuSearchHistory "$(json_num "${WEB_MENU_SEARCH_HISTORY}" 6)" \
  --arg AmapKey "${WEB_MAP_AMAP_KEY}" \
  --argjson MapResizeEnable "$(json_bool "${WEB_MAP_RESIZE_ENABLE}")" \
  --argjson MapCenterLng "$(json_num "${WEB_MAP_CENTER_LNG}" 113.6401)" \
  --argjson MapCenterLat "$(json_num "${WEB_MAP_CENTER_LAT}" 34.72468)" \
  --argjson MapZoom "$(json_num "${WEB_MAP_ZOOM}" 12)" \
  '{
    Version: $Version,
    Title: $Title,
    FixedHeader: $FixedHeader,
    HiddenSideBar: $HiddenSideBar,
    MultiTagsCache: $MultiTagsCache,
    KeepAlive: $KeepAlive,
    Locale: $Locale,
    Layout: $Layout,
    Theme: $Theme,
    DarkMode: $DarkMode,
    ThemeMode: $ThemeMode,
    Grey: $Grey,
    Weak: $Weak,
    HideTabs: $HideTabs,
    HideFooter: $HideFooter,
    Stretch: $Stretch,
    SidebarStatus: $SidebarStatus,
    EpThemeColor: $EpThemeColor,
    ShowLogo: $ShowLogo,
    Watermark: $Watermark,
    WatermarkText: $WatermarkText,
    TagsStyle: $TagsStyle,
    MenuArrowIconNoTransition: $MenuArrowIconNoTransition,
    CachingAsyncRoutes: $CachingAsyncRoutes,
    TooltipEffect: $TooltipEffect,
    ResponsiveStorageNameSpace: $ResponsiveStorageNameSpace,
    MenuSearchHistory: $MenuSearchHistory,
    MapConfigure: {
      amapKey: $AmapKey,
      options: {
        resizeEnable: $MapResizeEnable,
        center: [$MapCenterLng, $MapCenterLat],
        zoom: $MapZoom
      }
    }
  }' > "${CONFIG_FILE}"

echo "[web] generated ${CONFIG_FILE}"
