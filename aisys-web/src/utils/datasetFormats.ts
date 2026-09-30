/**
 * 数据集源/目标格式定义（与原项目 aisys-dataset DatasetFormat.java 对齐）。
 * 每种任务类型对应一组源格式（创建数据集时选择）与目标格式（版本下载/转换时选择）。
 */
export const DatasetFormatUtil = {
  /** 任务类型 → 源格式（数据集本身的格式） */
  sourceFormats: {
    image_classification: [
      { value: "imagenet", label: "ImageNet（文件夹分类）" },
      { value: "csv", label: "CSV（路径+标签）" },
      { value: "json_manifest", label: "JSON Manifest" }
    ],
    time_series: [
      { value: "ucr_ts", label: "UCR/UEA (.ts)" },
      { value: "ucr_tsv", label: "UCR TSV (.tsv)" },
      { value: "csv", label: "CSV" },
      { value: "arff", label: "ARFF (.arff)" }
    ],
    object_detection: [
      { value: "coco", label: "COCO JSON" },
      { value: "yolo", label: "YOLO TXT" },
      { value: "voc", label: "VOC XML" }
    ]
  } as Record<string, { value: string; label: string }[]>,

  /**
   * 任务类型 → 目标格式（下载/转换）。
   * 注意：只列后端 FormatConverters 真正能转换的格式（csv / coco / yolo / voc）。
   * imagenet/json_manifest/ucr_ts/ucr_tsv 后端未实现转换（会原样输出 jsonl），故不在下载选项暴露。
   */
  targetFormats: {
    image_classification: [
      { value: "csv", label: "CSV（路径+标签）" }
    ],
    time_series: [
      { value: "csv", label: "CSV" }
    ],
    object_detection: [
      { value: "coco", label: "COCO JSON" },
      { value: "yolo", label: "YOLO TXT" },
      { value: "voc", label: "VOC XML" }
    ]
  } as Record<string, { value: string; label: string }[]>,

  getSourceFormats(taskType?: string) {
    return (taskType && this.sourceFormats[taskType]) || [];
  },

  getTargetFormats(taskType?: string) {
    return (taskType && this.targetFormats[taskType]) || [];
  },

  /** 格式值 → 中文标签 */
  formatLabel(value?: string) {
    if (!value) return "-";
    for (const list of [
      ...Object.values(this.sourceFormats),
      ...Object.values(this.targetFormats)
    ]) {
      const hit = list.find(f => f.value === value);
      if (hit) return hit.label;
    }
    return value;
  }
};

/** 任务类型选项（与原项目一致：image_classification / time_series / object_detection） */
export const TASK_TYPE_OPTIONS = [
  { value: "image_classification", label: "图像分类" },
  { value: "time_series", label: "时序分类" },
  { value: "object_detection", label: "目标检测" }
];

/** 任务类型 → 中文标签 */
export const taskTypeLabel = (t?: string) => {
  const hit = TASK_TYPE_OPTIONS.find(o => o.value === t);
  return hit ? hit.label : t || "-";
};
