<script setup lang="ts">
import { ref } from "vue";

defineOptions({ name: "DatasetFormatGuide" });

interface FormatDetail {
  value: string;
  label: string;
  /** 实际目录结构：ZIP 解压后的文件夹/文件树 */
  structure: string;
  /** 关键文件格式：标注/清单文件内部的内容格式（无独立标注文件则为空） */
  fileFormat?: string;
  /** 关键文件名标注（如 data.yaml / annotations.json），用于标题 */
  fileFormatTitle?: string;
  notes: string[];
}
interface TaskTypeInfo {
  value: string;
  label: string;
  formats: FormatDetail[];
}

const taskTypes: TaskTypeInfo[] = [
  {
    value: "image_classification",
    label: "图像分类",
    formats: [
      {
        value: "imagenet",
        label: "ImageNet（文件夹分类）",
        structure: `dataset/
├── train/
│   ├── cat/
│   │   ├── cat_001.jpg
│   │   └── cat_002.jpg
│   └── dog/
│       └── dog_001.jpg
├── val/
│   ├── cat/
│   │   └── cat_003.jpg
│   └── dog/
│       └── dog_002.jpg
└── test/                # 可选`,
        notes: [
          "无独立标注文件：类别即子文件夹名",
          "须包含 train / val 划分（test 可选）",
          "支持 jpg / jpeg / png / bmp"
        ]
      },
      {
        value: "csv",
        label: "CSV（路径 + 标签）",
        structure: `dataset/
├── data.csv             # 清单：图片相对路径 + 标签
└── images/
    ├── cat_001.jpg
    ├── dog_001.jpg
    └── cat_002.jpg`,
        fileFormatTitle: "data.csv",
        fileFormat: `file_path,label,split
images/cat_001.jpg,cat,train
images/dog_001.jpg,dog,train
images/cat_002.jpg,cat,val`,
        notes: [
          "file_path 为相对 ZIP 根的路径",
          "标签列名：label / class / category / target",
          "split 列可选（默认 train）"
        ]
      },
      {
        value: "json_manifest",
        label: "JSON Manifest",
        structure: `dataset/
├── manifest.json        # 清单：JSON 数组，每项一张图
└── images/
    ├── cat_001.jpg
    └── dog_001.jpg`,
        fileFormatTitle: "manifest.json",
        fileFormat: `[
  {"file":"images/cat_001.jpg","label":"cat","split":"train"},
  {"file":"images/dog_001.jpg","label":"dog","split":"val"}
]`,
        notes: ["file 为相对路径", "标签：label / class / category", "split 可选"]
      }
    ]
  },
  {
    value: "object_detection",
    label: "目标检测",
    formats: [
      {
        value: "yolo",
        label: "YOLO TXT",
        structure: `dataset/
├── data.yaml            # 数据集配置（类别名、train/val 路径）
├── images/
│   ├── train/
│   │   └── 001.jpg
│   └── val/
│       └── 002.jpg
└── labels/
    ├── train/
    │   └── 001.txt      # 与图片同名
    └── val/
        └── 002.txt`,
        fileFormatTitle: "labels/train/001.txt  /  data.yaml",
        fileFormat: `# labels/train/001.txt  每行一个目标：class_id cx cy w h（归一化 0~1）
0 0.50 0.50 0.30 0.40
1 0.20 0.10 0.10 0.20

# data.yaml
path: .
train: images/train
val: images/val
names:
  0: person
  1: car`,
        notes: [
          "每张图对应同名 .txt 标注（001.jpg ↔ 001.txt）",
          "坐标归一化到 [0,1]：cx, cy 为中心点，w, h 为宽高",
          "data.yaml 的 names 给出类别 id→名称映射"
        ]
      },
      {
        value: "coco",
        label: "COCO JSON",
        structure: `dataset/
├── annotations/
│   └── instances.json   # 单个 JSON 含 images/annotations/categories
├── train2017/
│   ├── 000000001.jpg
│   └── 000000002.jpg
└── val2017/
    └── 000000003.jpg`,
        fileFormatTitle: "annotations/instances.json",
        fileFormat: `{
  "images": [
    {"id":1,"file_name":"train2017/000000001.jpg","width":640,"height":480}
  ],
  "annotations": [
    {"id":1,"image_id":1,"category_id":1,"bbox":[x,y,w,h],"area":1200,"iscrowd":0}
  ],
  "categories": [
    {"id":1,"name":"person"}
  ]
}`,
        notes: [
          "bbox=[x,y,w,h]：左上角坐标 + 宽高（绝对像素）",
          "坐标原点为图像左上角",
          "area 为 bbox 面积，categories 列出全部类别"
        ]
      },
      {
        value: "voc",
        label: "VOC XML",
        structure: `dataset/
├── JPEGImages/
│   ├── 001.jpg
│   └── 002.jpg
├── Annotations/
│   ├── 001.xml         # 每图一个 XML
│   └── 002.xml
└── ImageSets/
    └── Main/
        ├── train.txt    # 列出训练集图片名（不含扩展名）
        └── val.txt`,
        fileFormatTitle: "Annotations/001.xml",
        fileFormat: `<annotation>
 <filename>001.jpg</filename>
 <size><width>640</width><height>480</height><depth>3</depth></size>
 <object>
   <name>person</name>
   <bndbox>
     <xmin>100</xmin><ymin>50</ymin>
     <xmax>300</xmax><ymax>400</ymax>
   </bndbox>
 </object>
</annotation>`,
        notes: [
          "每张图一个同名 XML 标注文件",
          "bndbox 为绝对像素坐标（xmin/ymin/xmax/ymax）",
          "ImageSets/Main/{train,val}.txt 列出对应划分的图片名"
        ]
      }
    ]
  },
  {
    value: "time_series",
    label: "时间序列",
    formats: [
      {
        value: "ucr_ts",
        label: "UCR/UEA (.ts)",
        structure: `dataset/
├── train.ts
└── test.ts              # 可选`,
        fileFormatTitle: "train.ts",
        fileFormat: `@problemName Demo
@classLabel true person car
@data
1.0,0.5,0.3,0.8:0
2.1,0.1,0.9,0.4:1`,
        notes: [
          "UCR/UEA 标准 .ts 格式",
          "首行为 @ 元信息（@problemName / @classLabel 等）",
          "@data 之后每行一条时序，末尾冒号后为类别"
        ]
      },
      {
        value: "ucr_tsv",
        label: "UCR TSV (.tsv)",
        structure: `dataset/
├── train.tsv
└── test.tsv`,
        fileFormatTitle: "train.tsv",
        fileFormat: `0\t0.1\t0.5\t0.3\t0.8
1\t0.2\t0.1\t0.9\t0.4`,
        notes: [
          "UCR 标准 TSV 格式",
          "第一列为类别（整数），后续列为时序值，tab 分隔"
        ]
      },
      {
        value: "csv",
        label: "CSV（每样本一文件）",
        structure: `dataset/
├── 001.csv
├── 002.csv
└── 003.csv`,
        fileFormatTitle: "001.csv",
        fileFormat: `timestamp,value
0,1.2
1,1.5
2,1.8`,
        notes: [
          "每个 CSV 文件为一条样本",
          "含 timestamp + value 列（可多变量多列）",
          "文件名即为样本 id"
        ]
      },
      {
        value: "arff",
        label: "ARFF (.arff)",
        structure: `dataset/
├── train.arff
└── test.arff`,
        fileFormatTitle: "train.arff",
        fileFormat: `@relation timeseries
@attribute class {0,1}
@attribute t1 numeric
@attribute t2 numeric
@data
0,1.2,1.5
1,2.1,0.1`,
        notes: ["WEKA ARFF 格式", "@relation / @attribute 声明关系与字段", "@data 之后为实例数据"]
      }
    ]
  }
];

const activeTab = ref("image_classification");
</script>

<template>
  <div class="p-4">
    <el-card shadow="never">
      <template #header>
        <span class="text-lg font-bold">数据格式说明</span>
      </template>
      <el-alert
        type="info"
        :closable="false"
        class="mb-4"
        title="上传数据集时请将整个数据集目录打包为 ZIP，系统会自动解压。下面分别给出「目录结构」（ZIP 解压后的文件树）与「关键文件格式」（标注/清单文件内部的内容规范）。"
      />
      <el-tabs v-model="activeTab">
        <el-tab-pane v-for="tt in taskTypes" :key="tt.value" :label="tt.label" :name="tt.value">
          <el-card v-for="fmt in tt.formats" :key="fmt.value" shadow="hover" class="mb-4">
            <template #header>
              <span class="font-bold">{{ fmt.label }}</span>
              <el-tag size="small" type="info" class="ml-2">{{ fmt.value }}</el-tag>
            </template>

            <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
              <!-- 目录结构 -->
              <div>
                <p class="text-sm font-bold mb-2">📦 目录结构</p>
                <pre class="format-code">{{ fmt.structure }}</pre>
              </div>

              <!-- 关键文件格式（无独立标注文件则不显示，改列说明） -->
              <div>
                <p class="text-sm font-bold mb-2">
                  📄 关键文件格式
                  <span v-if="fmt.fileFormatTitle" class="file-name">（{{ fmt.fileFormatTitle }}）</span>
                </p>
                <pre v-if="fmt.fileFormat" class="format-code">{{ fmt.fileFormat }}</pre>
                <el-alert
                  v-else
                  type="warning"
                  :closable="false"
                  title="该格式无独立标注/清单文件，类别信息直接体现在目录结构中"
                />
              </div>
            </div>

            <!-- 说明 -->
            <div class="mt-3">
              <p class="text-sm font-bold mb-2">说明</p>
              <ul class="format-notes">
                <li v-for="(note, i) in fmt.notes" :key="i">{{ note }}</li>
              </ul>
            </div>
          </el-card>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.format-code {
  margin: 0;
  padding: 12px;
  background: #1e1e1e;
  color: #d4d4d4;
  border-radius: 6px;
  font-size: 12px;
  font-family: "Consolas", monospace;
  line-height: 1.5;
  overflow-x: auto;
  white-space: pre;
}
.format-notes {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
}
.format-notes li {
  margin-bottom: 4px;
}
.file-name {
  font-weight: 400;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
