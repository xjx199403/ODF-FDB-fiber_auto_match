# ODF-FDB-fiber_auto_match
该程序是通信业务专用，使用java+paraphrase-multilingual-MiniLM-L12-v2模型配合。针对通信系统中，设备跳纤混乱的情况，我们如果已经采集到了端口和对应的业务信息，可分析业务信息，并实现自动跳纤

因为业务信息包含各类错综复杂的信息，包括路由信息：大道东1-TPA2EG24-5R:1-1-2-19-7/8T0:1-1-2-25-11/12 业务信息： 大郡3期采光盒2BBU大道东基站5G  分光器信息：POS003/0UT01
用模型去训练的效果很差，模型无法理解 分光器1与分光器2是完全不一样的两个东西，因此这里只有用程序去添加关键词（地址，通信设备等），配合惩戒降级机制，实现关键词敏感，关键词+编号 敏感
最终实现效果如下：
![Image text](https://github.com/xjx199403/ODF-FDB-fiber_auto_match/blob/main/match.png)

