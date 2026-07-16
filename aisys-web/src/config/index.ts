import axios from "axios";
import type { App } from "vue";

const DEFAULT_PLATFORM_CONFIG: PlatformConfigs = {
  Version: "7.0.0",
  Title: "Pure",
  FixedHeader: true,
  HiddenSideBar: false,
  MultiTagsCache: false,
  KeepAlive: true,
  Locale: "zh",
  Layout: "vertical",
  Theme: "light",
  DarkMode: false,
  ThemeMode: "light",
  Grey: false,
  Weak: false,
  HideTabs: false,
  HideFooter: false,
  Stretch: false,
  SidebarStatus: true,
  EpThemeColor: "#409EFF",
  ShowLogo: true,
  Watermark: false,
  TagsStyle: "chrome",
  MenuArrowIconNoTransition: false,
  CachingAsyncRoutes: false,
  TooltipEffect: "light",
  ResponsiveStorageNameSpace: "responsive-",
  MenuSearchHistory: 6,
  MapConfigure: {
    amapKey: "adc139d56406f3844c8f1cf1c6b65c41",
    options: {
      resizeEnable: true,
      center: [113.6401, 34.72468],
      zoom: 12
    }
  }
};

let config: PlatformConfigs = { ...DEFAULT_PLATFORM_CONFIG };
const { VITE_PUBLIC_PATH } = import.meta.env;

const setConfig = (cfg?: unknown) => {
  if (cfg && typeof cfg === "object") {
    config = Object.assign({}, config, cfg);
  }
};

const getConfig = (key?: string): any => {
  if (typeof key === "string") {
    const arr = key.split(".");
    if (arr && arr.length) {
      let data = config;
      arr.forEach(v => {
        if (data && typeof data[v] !== "undefined") {
          data = data[v];
        } else {
          data = null;
        }
      });
      return data;
    }
  }
  return config;
};

/** Load runtime platform config, fall back to defaults when unavailable. */
export const getPlatformConfig = async (app: App): Promise<PlatformConfigs> => {
  app.config.globalProperties.$config = getConfig();
  return axios({
    method: "get",
    url: `${VITE_PUBLIC_PATH}platform-config.json`
  })
    .then(({ data: remoteConfig }) => {
      let $config = app.config.globalProperties.$config;
      if (app && $config && typeof remoteConfig === "object") {
        $config = Object.assign({}, DEFAULT_PLATFORM_CONFIG, $config, remoteConfig);
        app.config.globalProperties.$config = $config;
        setConfig($config);
      }
      return getConfig();
    })
    .catch(error => {
      console.warn(
        "[platform-config] failed to load platform-config.json, using defaults",
        error
      );
      app.config.globalProperties.$config = getConfig();
      return getConfig();
    });
};

const responsiveStorageNameSpace = () => getConfig().ResponsiveStorageNameSpace;

export { getConfig, setConfig, responsiveStorageNameSpace };
