package com.ks.yzy.app.service;

import com.hankcs.hanlp.HanLP;
import com.ks.yzy.app.dao.AppTestDao;
import com.ks.yzy.app.entity.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 智能跳纤
 * 针对通信设备（ODF,光交箱，分光器）中的标签业务信息，如：
 * 本端： 中国人寿保险苏州工业园区支公司3-817-苏州大道东1-TPA2EG24-5R:1-1-2-19-7/8T0:1-1-2-25-11/12  =》 找出from7/8端口和to端口11/12 分别成对
 * 本端： 1/望亭 POS003/0UT01 =》 找出分光器对应端口成对
 * 本端： 月光码头点位1E频段BOCK 对端：月光码头点位1.E频段BOOK  =》 多对相似业务信息，可自动分别成对
 * 本端： 东湖大郡3期采光盒2BBU苏州大道东基站5G 对端： 苏州大道东基站5GBBU东湖大郡3期采光盒2   =》 识别到关键词“采光盒” 按照采光盒编号成对
 * 分析业务信息，自动完成跳纤
 */
@Service
public class AutoFiberMatchService {

    @Autowired
    private AppTestDao appTestDao;
    private static final Pattern RULE1 = Pattern.compile("(?i)(?<![A-Z])(?:FR|F|R|TO|T0)[,:\\.]");
    private static  double STAND_SEMANTIC_PERCENT = 0.98D;
    private static  double MODEL_SEMANTIC_PERCENT = 0.9D;
    private static final Pattern RULE_POS = Pattern.compile("(?i)\\b(?:pos|p0s|os)[0o]*([1-9])\\b"); // 业务中的pos匹配
    private static final Pattern outPattern = Pattern.compile("(?i)(?:out|0ut)[0o]*([1-9][0-9]?)");
    private static final Pattern inPattern = Pattern.compile("(?i)(?:in|ln|1n)[0o]*([1-9][0-9]?)");
    private static final Pattern outPatternDb = Pattern.compile("(?i)\\bout/0*([1-9][0-9]*)\\b");
    private static final Pattern inPatternDb = Pattern.compile("(?i)\\bin/0*([1-9][0-9]*)\\b");
    private static final String[] RULE2_TOKENS = {"ODF", "O1F", "DF","FR","TO","01F"};
    private static final String[] afterFrAndCouldNotTextMatch = {"ODF", "O1F", "DF","FR","TO","F:","T0","01F","R:","TO:","T0:"};
    private static final List<OcrPort> portsFrTo = new ArrayList<>();
    private static final List<OcrPort> portsPos = new ArrayList<>();
    private static List<OcrPort> portsAllTemp = new ArrayList<>();
    private static List<OcrPort> portsAll = new ArrayList<>();
    private static List<MyCreateVo> illegalPosList = new ArrayList<>();
    private List<List<OcrPort>> pythonSemanticDealList = new ArrayList<>();
    private final DecimalFormat format = new DecimalFormat("#.0000");
    private final DecimalFormat format2 = new DecimalFormat("#.00");

    public List<OcrPort> excute(String phyEqpId, Map<String, Double> config, Double standModelPercent, Double standSematicPercent) throws Exception {
        System.out.println(standModelPercent);
        System.out.println(standSematicPercent);
        STAND_SEMANTIC_PERCENT  = standModelPercent;
        MODEL_SEMANTIC_PERCENT  = standSematicPercent;
        portsAllTemp = new ArrayList<>();
        pythonSemanticDealList = new ArrayList<>();
        /**
         *  ============ 获取基础数据  ============
         */
        List<String> units = appTestDao.getUnit(phyEqpId);
        for(int i = 0; i < units.size(); i++) {
            int ii = i + 1;
            List<OcrPort> ports =  appTestDao.getEqpByUnit(phyEqpId, units.get(i));
            ports.stream().forEach(port->port.setMian(ii));
            portsAllTemp.addAll(ports);
        }
        /**
         * ============ 获取分光器数据 ============
         */
        List<MyCreateVo> fgqList = appTestDao.getPosPorts(phyEqpId);
        List<MyCreateVo> legalPosList = fgqList.stream().filter(obj -> {
            String text = obj.getNo();
            String posName = obj.getPhyEqpName();
            Matcher outMatcher = outPatternDb.matcher(text);
            Matcher inMatcher = inPatternDb.matcher(text);

            Pattern posPatternDb = Pattern.compile("(?i)\\b(?:pos|p0s|os)([0-9o]+)");
            Matcher posNameMatcher = posPatternDb.matcher(posName);

            if(posNameMatcher.find()){
                int originalNumber = Integer.valueOf(posNameMatcher.group(1));
                // 如果大于20，只取最后一位 不会有超过20个分光器的情况
                if (originalNumber > 20) {
                    obj.setPosNo(originalNumber % 10);  // 取最后一位
                } else {
                    obj.setPosNo(originalNumber);
                }
            }else {
                obj.setPosNo(null);
                return false;
            }
            if (outMatcher.find()) {
                obj.setPortNo(obj.getPosNo() + "-" + "out"+ outMatcher.group(1));
                return true;
            } else if (inMatcher.find()) {
                obj.setPortNo(obj.getPosNo() + "-" + "in"+ inMatcher.group(1));
                return true;
            } else {
                illegalPosList.add(obj);
                obj.setInOrOut(null);
                obj.setPortNo(null);
                return false;
            }
        }).collect(Collectors.toList());
        Map<String, MyCreateVo> legalPosListMap = legalPosList.stream()
                .collect(Collectors.toMap(
                        MyCreateVo::getPortNo,
                        obj -> obj,
                        (v1, v2) -> v1
                ));
        List<OcrPort> OcrPorts = portsAllTemp.stream().filter(f -> f.getBusinessName() != null && !"无识别结果".equals(f.getBusinessName()) && f.getBusinessName().length() >= 4).collect(Collectors.toList());
        OcrPorts.stream().forEach(port->port.setBusinessName(port.getBusinessName().replaceAll("[\\s]", "")));
        portsAll = OcrPorts.stream()
                .map(OcrPort::clone)
                .collect(Collectors.toList());
        /**
         *  ============ 分光器匹配 ===========
         */
        filterPOS(OcrPorts);
        for(int i = 0; i < portsPos.size(); i++) {
            POSMatchVo pos = portsPos.get(i).getPosMatchVo();
            String key = pos.getPosNo()+"-"+pos.getInOrOut()+pos.getPortNo();
            if(legalPosListMap.containsKey(key)) {
                setPlpPos(portsPos.get(i), legalPosListMap.get(key).getPhyPortId(), key,legalPosListMap.get(key).getPhyEqpId());
            }
        }
        /**
         *  ============ 路由匹配 ============
         */
        OcrPorts = filterFrTo(OcrPorts);
        filterRoute(OcrPorts);
        for(int i = 0; i < portsFrTo.size(); i++) {
//            if(portsFrTo.get(i).getPhyPortId().equals("752952191001496769")) {
//            }
            List<AddrResult> list = extract(portsFrTo.get(i).getBusinessName());
            if(list.size() < 2) { // 长度有问题不处理
                continue;
            }
            if(list.size() > 2) { // 分段路由处理
                list = extractMultiple(portsFrTo.get(i).getBusinessName());
                if(list.size() % 2 != 0) { // 长度有问题不处理
                    continue;
                }
                int half = list.size() / 2;
                for (int j = 0; j < half; j++) {
                    setPlpMulti(new String[]{
                            list.get(j).value,
                            list.get(j + half).value
                    });
                }
                continue;
            }
            if(list.get(0).hasSlash && list.get(1).hasSlash) {
                setPlpDouble(new String[]{list.get(0).value, list.get(1).value});
                continue;
            }
            if(!list.get(0).hasSlash && !list.get(1).hasSlash) {
                setPlp(new String[]{list.get(0).value, list.get(1).value});
            }
        }
        /**
         *  ============ 语义相似度匹配 ===========
         */
        for(int i = 0; i < portsAll.size(); i++) {
            OcrPort o1 = portsAll.get(i);
            if(o1.getPlpPortId() != null || o1.getPlpNo() != null) {
                continue;
            }
            o1.setNameSamePercent(0D);
            if(containsAtLeastTwoFrKeywords(o1.getBusinessName(), afterFrAndCouldNotTextMatch)){ // 非法业务
                continue;
            }
            List<OcrPort> samePorts = new ArrayList<>(); // 临时存储相似业务
            samePorts.add(o1);
            for(int j = 0; j < portsAll.size(); j++) {
                if(portsAll.get(j).getPlpPortId() != null || portsAll.get(i).getPhyPortId().equals(portsAll.get(j).getPhyPortId())) {  // 对方已被匹配
                    continue;
                }
                OcrPort o2 = portsAll.get(j);
                if(containsAtLeastTwoFrKeywords(o2.getBusinessName(), afterFrAndCouldNotTextMatch)) { // 对方非法字符
                    continue;
                }
                double semanticResult = getSimilarity(o1.getBusinessName(),o2.getBusinessName()); // 基本语义匹配
                if(semanticResult > STAND_SEMANTIC_PERCENT) {
                    o2.setNameSamePercent(Double.parseDouble(format.format(semanticResult)));
                    samePorts.add(o2);
                }
            }
            setPlpYuyiDouble(samePorts, false);
        }
        /**
         *  ============ 相同盘移除 ===========
         */
        for(OcrPort port : portsAll) {
            if(port.getPlpPortId() != null) {
                for(OcrPort portInner : portsAll) {
                    if(portInner.getPhyPortId().equals(port.getPlpPortId())) {
                        if(isSinglePan(port,portInner)) {
                            removePlp(port);
                            removePlp(portInner);
                        }

                    }
                }
            }
            if(!port.isPlpFlag()){
                removePlp(port);
            }
        }
        /**
         *  ============ 大模型匹配 step1:筛选 ===========
         */
        for(int i = 0; i < portsAll.size(); i++) {
            OcrPort o1 = portsAll.get(i);
            if(o1.isInSamePorts()) {
                continue;
            }
            if(o1.getPlpPortId() != null || o1.getPlpNo() != null) {
                continue;
            }
            o1.setNameSamePercent(0D);
            if(containsAtLeastTwoFrKeywords(o1.getBusinessName(), afterFrAndCouldNotTextMatch)) {
                continue;
            }
            List<OcrPort> samePorts = new ArrayList<>();
            o1.setInSamePorts(true);
            samePorts.add(o1);
            for(int j = 0; j < portsAll.size(); j++) {
                OcrPort o2 = portsAll.get(j);
                if(o2.isInSamePorts()) {
                    continue;
                }
                // 已跳纤不处理
                if(portsAll.get(j).getPlpPortId() != null || portsAll.get(i).getPhyPortId().equals(portsAll.get(j).getPhyPortId())) {
                    continue;
                }
                // 路由业务不处理
                if(containsAtLeastTwoFrKeywords(o2.getBusinessName(), afterFrAndCouldNotTextMatch)) {
                    continue;
                }
                double semanticResult = getSimilarity(o1.getBusinessName(),o2.getBusinessName());
                if(semanticResult < 0.8D) {
                    continue; // no useful but its ok
                }else {
                    o2.setInSamePorts(true);
                    samePorts.add(o2);
                }
            }
            if(samePorts.size() == 1) continue;
            pythonSemanticDealList.add(samePorts);
        }
        SimilarityRequest req = new SimilarityRequest();
        req.setKeywords(config);

        System.out.println("需要处理的语义数量：" + pythonSemanticDealList.size());
        /**
         *  ============ 大模型匹配 step2:处理 ===========
         */
        if(pythonSemanticDealList.size() <= 200) { // 过长的语义识别数据长度会导致崩溃，如果太多数据就暂时不要做了
            // 创建线程池
            ExecutorService executor = Executors.newFixedThreadPool(1);
            List<Future<?>> futures = new ArrayList<>();

            for (List<OcrPort> ports : pythonSemanticDealList) {
                System.out.println("========================================================");
                for (int i = 0; i < ports.size(); i++) {
                    final int index = i;
                    Future<?> future = executor.submit(() -> {
                        OcrPort o1 = ports.get(index);
                        if (o1.getPlpPortId() != null || o1.getPlpNo() != null) {
                            return;
                        }
                        List<OcrPort> samePorts = new ArrayList<>();
                        samePorts.add(o1);

                        for (int j = 0; j < ports.size(); j++) {
                            if (index == j) continue;

                            OcrPort o2 = ports.get(j);
                            req.setSentence1(o1.getBusinessName());
                            req.setSentence2(o2.getBusinessName());

                            try {
                                SimilarityClient.ResponseData responseData = SimilarityClient.getSimilarity(req);
                                double similarity = responseData.getFin();
                                if (similarity >= MODEL_SEMANTIC_PERCENT) {
                                    DecimalFormat format2 = new DecimalFormat("0.00");
                                    String sematic = format2.format(responseData.getSemantic() * 100);

                                    synchronized (o2) {
                                        if (StringUtils.isEmpty(responseData.getMatched_keywords())) {
                                            o2.setPlpType("模型匹配成功，语义匹配度：" + sematic + "%");
                                        } else {
                                            String fin = format2.format(Math.min(responseData.getFin() * 100, 100));
                                            o2.setPlpType("模型匹配成功，语义匹配度：" + sematic + "%,识别到关键词：《" +
                                                    responseData.getMatched_keywords() + "》，辅助匹配度：" + fin + "%");
                                        }
                                        o2.setNameSamePercent(Double.parseDouble(format2.format(similarity)));
                                    }
                                    samePorts.add(o2);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        setPlpYuyiDouble(samePorts, true);
                    });
                    futures.add(future);
                }
            }
            // 等待所有任务完成
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            executor.shutdown();
        }

        // 程序结束时关闭HttpClient
         SimilarityClient.close();
        /**
         *  ============ 相同盘移除 ===========
         */
        for(OcrPort port : portsAll) {
            if(port.getPlpPortId() != null) {
                for(OcrPort portInner : portsAll) {
                    if(portInner.getPhyPortId().equals(port.getPlpPortId())) {
                        if(isSinglePan(port,portInner)) {
                            removePlp(port);
                            removePlp(portInner);
                        }
                    }
                }
            }
            if(!port.isPlpFlag()){
                removePlp(port);
            }
        }
        return portsAll.stream().filter(port->port.getPlpNo() != null).map(s -> {
            int index = s.getNo().indexOf("-");
            s.setNo(index >= 0 ? s.getNo().substring(index + 1) : s.getNo());
            if(s.getPlpBusinessName() != null && !s.getPlpBusinessName().equals("-")) {
                int indexPlp = s.getPlpNo().indexOf("-");
                s.setPlpNo(indexPlp >= 0 ? s.getPlpNo().substring(indexPlp + 1) : s.getPlpNo());
            }
            if(s.getNameSamePercent()!= null){
                s.setNameSamePercent(s.getNameSamePercent()*100);
            }
            return s;
        }).collect(Collectors.toList());
    }


    /**
     *  语义成对 直接匹配(成对)
     */
    private void setPlpYuyiDouble(List<OcrPort> samePorts, boolean isModelDeal) {
//        for(OcrPort OcrPort: samePorts){ // debug
//            if (OcrPort.getPhyPortId().equals("752952191001496924")) {
//            }
//        }
        int minSize = samePorts.size()/2;
        samePorts = samePorts.stream().sorted(
                        Comparator.comparingDouble(OcrPort::getPan).thenComparing(OcrPort::getPhyPortId)
                ).collect(Collectors.toList());
        // 奇数总量减一
        if (samePorts.size() % 2 == 1) {
            if(!rmNotGroup(samePorts)){
                return;
            }
        }
        for (int i = 0; i < minSize; i++) {
            setPlpName(samePorts.get(i).getPhyPortId(), samePorts.get(i).getNo(), samePorts.get(minSize+i), isModelDeal);
        }

    }
    /**
     *  移除未分组
     */
    private boolean rmNotGroup(List<OcrPort> list) {
        List<List<OcrPort>> groups = new ArrayList<>();
        List<OcrPort> currentGroup = new ArrayList<>();
        currentGroup.add(list.get(0));
        for (int i = 1; i < list.size(); i++) {
            OcrPort prev = list.get(i - 1);
            OcrPort curr = list.get(i);
            int a1 = Integer.parseInt(curr.getPhyPortId().substring(curr.getPhyPortId().length() - 3));
            int a2 = Integer.parseInt(prev.getPhyPortId().substring(curr.getPhyPortId().length() - 3))+1;
            if (a1 == a2) {
                currentGroup.add(curr);
            } else {
                groups.add(new ArrayList<>(currentGroup));
                currentGroup.clear();
                currentGroup.add(curr);
            }
        }
        groups.add(currentGroup);
        if (groups.size() != 2) {
            return false;
        }
        if(groups.get(0).size()>groups.get(1).size()) {
            list.remove(groups.get(0).get(groups.get(0).size()-1));
        }else{
            list.remove(groups.get(1).get(groups.get(1).size()-1));
        }
        return true;
    }

    /**
     * 业务名称跳接
     * @param portId
     */
    private void setPlpName(String portId, String portNo, OcrPort plpPort, boolean isModelDeal) {
        for (OcrPort port : portsAll) {
            if(port.getPlpPortId() != null) {
                continue;
            }
            if(port.getPhyPortId().equals(portId)) {
                // 记得用plpType的数值
                port.setNameSamePercent(plpPort.getNameSamePercent());
                if(isModelDeal) {
                    port.setPlpRealType("model");
                    port.setPlpType(plpPort.getPlpType());
                }else {
                    port.setPlpType("可按照基本语义匹配,语义匹配度：" + format2.format(plpPort.getNameSamePercent()*100) + "%");
                    port.setPlpRealType("sematic");
                }
                port.setPlpBusinessName(plpPort.getBusinessName());
                port.setPlpPortId(plpPort.getPhyPortId());
                port.setPlpNo(plpPort.getNo());
                plpPort.setPlpPortId(port.getPhyPortId());
                plpPort.setPlpFlag(false);
                plpPort.setPlpNo(portNo);
                break;
            }
        }

    }

    /**
     * 分光器匹配
     * @param ocrPort
     * @param posPortId
     * @param posPortNo
     */
    private void setPlpPos(OcrPort ocrPort, String posPortId, String posPortNo, String plpEqpId) {
        for (OcrPort port : portsAll) {
            if(port.getPlpPortId() != null) {
                continue;
            }
            if(port.getPhyPortId().equals(ocrPort.getPhyPortId())) {
                port.setPlpType("识别到分光器："+"POS0" + posPortNo);
                port.setPlpRealType("pos");
                port.setPlpBusinessName("-");
                port.setPlpPortId(posPortId);
                port.setPlpNo("POS0" + posPortNo);
                port.setPlpEqpId(plpEqpId);
                break;
            }
        }
    }

    /**
     * 无斜杠匹配
     * @param values
     * @return
     */
    private void setPlp(String[] values) {
        try {
            Integer[] no = getNo(values[0]);
            for (OcrPort port : portsAll) {
                if(port.getPlpPortId() != null || port.getPlpNo() != null) { // 已匹配过的不再匹配
                    continue;
                }
                if (no[0].equals(port.getMian()) && no[1].equals(port.getPan()) && no[2].equals(port.getColumnNo())) {
                    if(!isFindDuankouBusinessLegalSingle(port.getBusinessName(), no)) { // 端口信息不符合预期
                        continue;
                    }
                    for(OcrPort portPair : portsAllTemp) {
                        if(portPair.getPlpPortId() != null || port.getPlpNo() != null) { // 已匹配过的不再匹配
                            continue;
                        }
                        Integer[] noPair = getNo(values[1]);
                        if (noPair[0].equals(portPair.getMian()) && noPair[1].equals(portPair.getPan()) && noPair[2].equals(portPair.getColumnNo())) {
                            if(!isFindDuankouBusinessLegalSingle(portPair.getBusinessName(), noPair)) { // 端口信息不符合预期
                                continue;
                            }
                            port.setPlpType("识别到单条路由，本条指向"+portPair.getNo());
                            port.setPlpRealType("route");
                            port.setPlpPortId(portPair.getPhyPortId());
                            port.setPlpNo(portPair.getNo());
                            port.setPlpBusinessName(portPair.getBusinessName());

                            portPair.setPlpPortId(port.getPhyPortId());
                            portPair.setPlpType("已被单条路由匹配");
                            portPair.setPlpFlag(false);
                            break;
                        }
                    }
                    break;
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     * 端口编号抽取
     * @param values
     * @return
     */
    Integer[] getNo(String values) {
        String[] parts = values.split("-");
        int mian = Integer.valueOf(parts[0]);
        int pan = Integer.valueOf(parts[1]);
        int col = Integer.valueOf(parts[2]);
        if (col > 12) {
            col = (col / 10) % 10;
        }
        return new Integer[]{mian, pan, col};
    }

    /**
     * 双端口编号抽取
     * @param parts
     * @return
     */
    Integer[] getNoDouble(String[] parts) {
        int mian = Integer.valueOf(parts[0]);
        int pan = Integer.valueOf(parts[1]);
        int col = Integer.valueOf(parts[2]);
        if (col > 12) {
            col = (col / 10) % 10;
        }
        return new Integer[]{mian, pan, col};
    }
    /**
     * 双端口匹配
     * @param values
     * @return
     */
    private void setPlpDouble(String[] values) {
        try {
            String[][] ports = splitFixedFormat(values[0]);
            Integer[] no00 = getNoDouble(ports[0]);
            Integer[] no01 = getNoDouble(ports[1]);
            String[][] portsPair = splitFixedFormat(values[1]);
            Integer[] no10 = getNoDouble(portsPair[0]);
            Integer[] no11 = getNoDouble(portsPair[1]);
            for (OcrPort port : portsAll) {
                if(port.getPlpPortId() != null || port.getPlpNo() != null) {
                    continue;
                }
                if (no00[0].equals(port.getMian()) && no00[1].equals(port.getPan()) && no00[2].equals(port.getColumnNo())) {
                    if(!isFindDuankouBusinessLegalDouble(port.getBusinessName(), no00)) {// 端口业务和实际端口是否一一对应
                        continue;
                    }
                    for(OcrPort portPair : portsAllTemp) {
                        if(portPair.getPlpPortId() != null  || port.getPlpNo() != null) {
                            continue;
                        }
                        if (no10[0].equals(portPair.getMian()) && no10[1].equals(portPair.getPan()) && no10[2].equals(portPair.getColumnNo())) {
//                            if(!isFindDuankouBusinessLegalDouble(portPair.getBusinessName(), no10)) { // 端口信息不符合预期 对端就算了
//                                continue;
//                            }
                            /**
                             *  双端口 同一排要特殊处理,例如 GJ9961-A-01-02-02 GJ9961-A-01-02-03 G19961-B-01-21-09 G19961-B-01-21-10
                             */
                            port.setPlpType("识别到两条路由信息，本条指向:"+portPair.getNo());
                            port.setPlpRealType("route");
                            port.setPlpBusinessName(portPair.getBusinessName());
                            port.setPlpPortId(portPair.getPhyPortId());
                            port.setPlpNo(portPair.getNo());

                            portPair.setPlpType("已被双条路由匹配");
                            portPair.setPlpPortId(port.getPhyPortId());
                            portPair.setPlpNo(port.getNo());
                            portPair.setPlpFlag(false);
                        }
                    }
                }
                if (no01[0].equals(port.getMian()) && no01[1].equals(port.getPan()) && no01[2].equals(port.getColumnNo())) {
                    if(!isFindDuankouBusinessLegalDouble(port.getBusinessName(), no01)) { // 端口信息不符合预期
                        continue;
                    }
                    for(OcrPort portPair : portsAllTemp) {
                        if(portPair.getPlpPortId() != null || port.getPlpNo() != null) {
                            continue;
                        }
                        if (no11[0].equals(portPair.getMian()) && no11[1].equals(portPair.getPan()) && no11[2].equals(portPair.getColumnNo())) {
                            port.setPlpType("识别到两条路由信息，本条指向:"+portPair.getNo());
                            port.setPlpRealType("route");
                            port.setPlpBusinessName(portPair.getBusinessName());
                            port.setPlpPortId(portPair.getPhyPortId());
                            port.setPlpNo(portPair.getNo());

                            portPair.setPlpType("已被双条路由匹配");
                            portPair.setPlpPortId(port.getPhyPortId());
                            portPair.setPlpNo(port.getNo());
                            portPair.setPlpFlag(false);
                            break;
                        }
                    }
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    // 分段路由匹配
    private void setPlpMulti(String[] values) {
        try {
            Integer[] no = getNo(values[0]);
            for (OcrPort port : portsAll) {
                if(port.getPlpPortId() != null || port.getPlpNo() != null) { // 已匹配过的不再匹配
                    continue;
                }
                if (no[0].equals(port.getMian()) && no[1].equals(port.getPan()) && no[2].equals(port.getColumnNo())) {
                    for(OcrPort portPair : portsAllTemp) {
                        if(portPair.getPlpPortId() != null || port.getPlpNo() != null) { // 已匹配过的不再匹配
                            continue;
                        }
                        Integer[] noPair = getNo(values[1]);
                        if (noPair[0].equals(portPair.getMian()) && noPair[1].equals(portPair.getPan()) && noPair[2].equals(portPair.getColumnNo())) {
                            port.setPlpType("识别到单条路由，本条指向"+portPair.getNo());
                            port.setPlpRealType("route");
                            port.setPlpPortId(portPair.getPhyPortId());
                            port.setPlpNo(portPair.getNo());
                            port.setPlpBusinessName(portPair.getBusinessName());

                            portPair.setPlpPortId(port.getPhyPortId());
                            portPair.setPlpType("已被单条路由匹配");
                            portPair.setPlpFlag(false);
                            break;
                        }
                    }
                    break;
                }
            }
        }catch (Exception e){
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }


    /**
     * 查看业务信息是否符合预期 双组端口
     * @param businessName 匹配到的端口的业务信息
     * @param originalPort 源信息
     * @return
     */
    private boolean isFindDuankouBusinessLegalDouble(String businessName, Integer[] originalPort) {
        try {
            List<AddrResult> list = extract(businessName);
            if(StringUtils.isEmpty(businessName) || "无标签".equals(businessName) || "无识别结果".equals(businessName)) { // 没有业务信息，也让它过
                return true;
            }
            if (list.size() != 2) {
                return false;
            }
            String[] values = new String[]{list.get(0).value, list.get(1).value};
            String[][] ports = splitFixedFormat(values[0]);
            Integer[] no00 = getNoDouble(ports[0]);
            Integer[] no01 = getNoDouble(ports[1]);
            String[][] portsPair = splitFixedFormat(values[1]);
            Integer[] no10 = getNoDouble(portsPair[0]);
            Integer[] no11 = getNoDouble(portsPair[1]);
            // 只要盘面有一组能匹配上就可以
            if (Objects.equals(no00[0], originalPort[0]) && Objects.equals(no00[1], originalPort[1])) {
                return true;
            }
            if (Objects.equals(no01[0], originalPort[0]) && Objects.equals(no01[1], originalPort[1])) {
                return true;
            }
            if (Objects.equals(no10[0], originalPort[0]) && Objects.equals(no10[1], originalPort[1])) {
                return true;
            }
            if (Objects.equals(no11[0], originalPort[0]) && Objects.equals(no11[1], originalPort[1])) {
                return true;
            }
        } catch (Exception e){
            return false;
        }
        return false;
    }

    /**
     * 是否是同一盘
     * @param ocrPort1
     * @param ocrPort2
     * @return
     */
    private boolean isSinglePan(OcrPort ocrPort1 ,OcrPort ocrPort2) {
        if(!ocrPort1.getMian().equals(ocrPort2.getMian())) {
            return false;
        }
        return ocrPort1.getPan().equals(ocrPort2.getPan());
    }

    /**
     * 移除对端
     * @param ocrPort
     */
    private void removePlp(OcrPort ocrPort) {
        ocrPort.setPlpPortId(null);
        ocrPort.setPlpType(null);
        ocrPort.setPlpNo(null);
        ocrPort.setPlpFlag(false);
        ocrPort.setPlpBusinessName(null);
    }

    /**
     * 查看业务信息是否符合预期 单组端口
     * @param businessName 匹配到的端口的业务信息
     * @param originalPort 源信息
     */
    private boolean isFindDuankouBusinessLegalSingle(String businessName, Integer[] originalPort) {
        try {
            if(StringUtils.isEmpty(businessName) || "无标签".equals(businessName) || "无识别结果".equals(businessName)) { // 只有非空的业务信息才看 业务是否符合当前的预期端口
                return true;
            }
            List<AddrResult> list = extract(businessName);
            if (list.size() != 2) {
                return false;
            }
            String[] values = new String[]{list.get(0).value, list.get(1).value};
            Integer[] no = getNo(values[0]);
            Integer[] noPair = getNo(values[1]);
            if (Objects.equals(no[0], originalPort[0]) && Objects.equals(no[1], originalPort[1])) {
                return true;
            }
            if (Objects.equals(noPair[0], originalPort[0]) && Objects.equals(noPair[1], originalPort[1])) {
                return true;
            }

        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * 格式化端口编号
     * @param input
     * @return
     */
    public static String[][] splitFixedFormat(String input) {
        String[] parts = input.split("-");  // [A, B, C/D]
        String a = parts[0];
        String b = parts[1];
        String[] cAndD = parts[2].split("/"); // [C, D]
        String[][] result = new String[2][3];
        result[0] = new String[]{a, b, cAndD[0]};
        result[1] = new String[]{a, b, cAndD[1]};
        return result;
    }

    /**
     * 设备提前正则属性
     * (?:T[O0]|FR): （4–5 段，最后 2 位数字，后面 3 字符内不能再有 ‘-’）。
     */
    private static final Pattern P = Pattern.compile(
            "(?i)(?:T[O0]|FR|R|ODF|O1F|DF)[,:.]?\\s*"
                    + "(?:([^\\d][^/]*)?/)?"
                    + "((?:\\d{1,2}-){3,4}\\d{1,2})(/\\d{1,2})?"
    );

    /**
     * 设备信息提取
     * @param text
     * @return
     */
    public static List<AddrResult> extract(String text) {
        List<AddrResult> result = new ArrayList<>();
        Matcher m = P.matcher(text);
        while (m.find()) {
            String mainAddr = m.group(2);
            String slashPart = m.group(3);
            String[] parts = mainAddr.split("-");
            if (parts.length >= 3) {
                String last3 = String.join("-", Arrays.copyOfRange(
                        parts, parts.length - 3, parts.length));
                if (slashPart != null) {
                    result.add(new AddrResult(last3 + slashPart, true));
                } else {
                    result.add(new AddrResult(last3, false));
                }
            }
        }
        return result;
    }
    public static double getSimilarity(String sentence1, String sentence2) {
        sentence1 = sentence1.toLowerCase();
        sentence2 = sentence2.toLowerCase();

        // 分词并过滤
        List<String> sent1Words = HanLP.segment(sentence1).stream()
                .map(a -> a.word).filter(s -> !"`~!@#$^&*()=|{}':;',\\[\\].<>/?~ ！ @# ￥…… &* （）—— |{} 【】'；：“” ' 。，、？ ".contains(s)).collect(Collectors.toList());
        List<String> sent2Words = HanLP.segment(sentence2).stream()
                .map(a -> a.word).filter(s -> !"`~!@#$^&*()=|{}':;',\\[\\].<>/?~ ！ @# ￥…… &* （）—— |{} 【】'；：“” ' 。，、？ ".contains(s)).collect(Collectors.toList());

        // 提取地址映射关系
        Map<String, String> addressMapping1 = extractAddressMapping(sent1Words);
        Map<String, String> addressMapping2 = extractAddressMapping(sent2Words);

        // 计算惩罚因子
        double penaltyFactor = calculatePenaltyFactor(addressMapping1, addressMapping2);

        // 识别地址词汇并赋予权重
        Map<String, Double> sent1WeightedWords = weightAddressWords(sent1Words);
        Map<String, Double> sent2WeightedWords = weightAddressWords(sent2Words);

        List<String> allWords = mergeList(sent1Words, sent2Words);

        // 使用加权统计
        double[] statistic1 = weightedStatistic(allWords, sent1WeightedWords);
        double[] statistic2 = weightedStatistic(allWords, sent2WeightedWords);

        double dividend = 0;
        double divisor1 = 0;
        double divisor2 = 0;
        for (int i = 0; i < statistic1.length; i++) {
            dividend += statistic1[i] * statistic2[i];
            divisor1 += Math.pow(statistic1[i], 2);
            divisor2 += Math.pow(statistic2[i], 2);
        }

        double rawSimilarity = dividend / (Math.sqrt(divisor1) * Math.sqrt(divisor2));

        // 应用惩罚因子
        return rawSimilarity * penaltyFactor;
    }

    /**
     * 提取地址映射关系（地址类型 -> 标识符）
     */
    private static Map<String, String> extractAddressMapping(List<String> words) {
        Map<String, String> addressMapping = new HashMap<>();

        // 地址相关词汇模式
        Set<String> addressPatterns = new HashSet<>(Arrays.asList(
                "小区", "栋", "号楼", "单元", "室", "路", "街", "巷", "弄", "号",
                "村", "组", "区", "县", "市", "省", "大道", "胡同", "庄园", "花园"
        ));

        // 标识符模式（数字或字母）
        String identifierPattern = "[A-Za-z0-9]+";

        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);

            // 检查是否是地址词
            for (String pattern : addressPatterns) {
                if (word.contains(pattern)) {
                    // 检查前一个词是否是标识符
                    if (i > 0 && words.get(i-1).matches(identifierPattern)) {
                        addressMapping.put(pattern, words.get(i-1));
                    }
                    // 检查后一个词是否是标识符
                    else if (i < words.size() - 1 && words.get(i+1).matches(identifierPattern)) {
                        addressMapping.put(pattern, words.get(i+1));
                    }
                    // 检查词本身是否包含标识符
                    else if (word.matches(".*[A-Za-z0-9]+.*")) {
                        // 提取标识符部分
                        String identifier = word.replaceAll("[^A-Za-z0-9]", "");
                        addressMapping.put(pattern, identifier);
                    }
                    break;
                }
            }
        }

        return addressMapping;
    }

    /**
     * 计算惩罚因子
     */
    private static double calculatePenaltyFactor(Map<String, String> mapping1, Map<String, String> mapping2) {
        double penaltyFactor = 1.0; // 初始无惩罚

        // 找出两个映射中都存在的地址类型
        Set<String> commonAddressTypes = new HashSet<>(mapping1.keySet());
        commonAddressTypes.retainAll(mapping2.keySet());

        if (commonAddressTypes.isEmpty()) {
            return penaltyFactor; // 没有共同的地址类型，无需惩罚
        }

        int mismatchCount = 0;

        // 检查每个共同地址类型的标识符是否匹配
        for (String addressType : commonAddressTypes) {
            String identifier1 = mapping1.get(addressType);
            String identifier2 = mapping2.get(addressType);

            if (identifier1 != null && identifier2 != null && !identifier1.equals(identifier2)) {
                mismatchCount++;
            }
        }

        // 根据不匹配数量计算惩罚因子
        // 每有一个不匹配，相似度减少50%
        if (mismatchCount > 0) {
            penaltyFactor = Math.pow(0.5, mismatchCount);
        }

        return penaltyFactor;
    }

    /**
     * 识别地址词汇并赋予权重
     */
    private static Map<String, Double> weightAddressWords(List<String> words) {
        Map<String, Double> weightedWords = new HashMap<>();

        // 地址相关词汇模式
        Set<String> addressPatterns = new HashSet<>(Arrays.asList(
                "小区", "栋", "号楼", "单元", "室", "路", "街", "巷", "弄", "号",
                "村", "组", "区", "县", "市", "省", "大道", "胡同", "庄园", "花园"
        ));

        // 标识符模式（数字或字母）
        String identifierPattern = "[A-Za-z0-9]+";

        // 第一次遍历：标记所有地址词和标识符词
        boolean[] isAddressWord = new boolean[words.size()];
        boolean[] isIdentifierWord = new boolean[words.size()];

        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);

            // 检查是否是地址词
            for (String pattern : addressPatterns) {
                if (word.contains(pattern)) {
                    isAddressWord[i] = true;
                    break;
                }
            }

            // 检查是否是标识符（纯数字或字母）
            if (word.matches(identifierPattern)) {
                isIdentifierWord[i] = true;
            }
        }

        // 第二次遍历：根据相邻关系分配权重
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            double weight = 1.0; // 默认权重

            // 检查当前词是否是地址词或标识符词
            boolean currentIsAddress = isAddressWord[i];
            boolean currentIsIdentifier = isIdentifierWord[i];

            // 检查相邻词
            boolean hasAdjacentIdentifier = false;
            boolean hasAdjacentAddress = false;

            // 检查前一个词
            if (i > 0) {
                hasAdjacentIdentifier = hasAdjacentIdentifier || isIdentifierWord[i - 1];
                hasAdjacentAddress = hasAdjacentAddress || isAddressWord[i - 1];
            }

            // 检查后一个词
            if (i < words.size() - 1) {
                hasAdjacentIdentifier = hasAdjacentIdentifier || isIdentifierWord[i + 1];
                hasAdjacentAddress = hasAdjacentAddress || isAddressWord[i + 1];
            }

            // 分配权重规则：
            if (currentIsAddress && hasAdjacentIdentifier) {
                // 地址词旁边有标识符：高权重
                weight = 5.0;
            } else if (currentIsIdentifier && hasAdjacentAddress) {
                // 标识符旁边有地址词：高权重
                weight = 5.0;
            } else if (currentIsAddress) {
                // 单独的地址词：中等权重
                weight = 3.0;
            } else if (currentIsIdentifier) {
                // 单独的标识符：不特别加权（保持1.0）
                weight = 1.0;
            }

            // 特殊情况：如果词本身同时包含标识符和地址模式（如"3号楼"、"A小区"）
            if (word.matches(".*[A-Za-z0-9]+.*")) {
                for (String pattern : addressPatterns) {
                    if (word.contains(pattern)) {
                        weight = 6.0; // 最高权重
                        break;
                    }
                }
            }

            weightedWords.put(word, weight);
        }

        return weightedWords;
    }

    /**
     * 加权统计
     */
    private static double[] weightedStatistic(List<String> allWords, Map<String, Double> weightedWords) {
        double[] result = new double[allWords.size()];
        for (int i = 0; i < allWords.size(); i++) {
            String word = allWords.get(i);
            double count = Collections.frequency(
                    weightedWords.keySet().stream().collect(Collectors.toList()),
                    word
            );
            double weight = weightedWords.getOrDefault(word, 1.0);
            result[i] = count * weight;
        }
        return result;
    }

    /**
     * 合并列表并去重
     */
    private static List<String> mergeList(List<String> list1, List<String> list2) {
        List<String> result = new ArrayList<>();
        result.addAll(list1);
        result.addAll(list2);
        return result.stream().distinct().collect(Collectors.toList());
    }
    /**
     * 过滤出 from to
     * @param lines
     * @return
     */
    public static List<OcrPort> filterFrTo(List<OcrPort> lines) {
        List<OcrPort> kept = new ArrayList<>();
        for (OcrPort line : lines) {
            if (!isFrTo(line.getBusinessName())) {
                kept.add(line);
            }else {
                portsFrTo.add(line);
            }
        }
        return kept;
    }

    /**
     * 是否是路由
     * @param line
     * @return
     */
    public static boolean isFrTo(String line) {
        String u = line.toUpperCase();
        // ① 命中规则 1 ：关键字 + 标点
        if (RULE1.matcher(u).find()) {
            return true;
        }
        // ② 命中规则 2 ：ODF/O1F/DF 至少出现 2 种
        int hit = 0;
        for (String tok : RULE2_TOKENS) {
            if (u.contains(tok)) {
                if (++hit >= 2) {// 出现 2 种即可提前返回
                    return true;
                }
            }
        }
        return false;
    }
    /**
     * 过滤出分光器
     * @param ports
     * @return
     */
    public static List<OcrPort> filterPOS(List<OcrPort> ports) {
        List<OcrPort> kept = new ArrayList<>();
        for (OcrPort port : ports) {
            POSMatchVo posResult = isPOS(port.getBusinessName());
            if (posResult == null) {
                kept.add(port);
            }else {
                port.setPosMatchVo(posResult);
                portsPos.add(port);
            }
        }
        return kept;
    }
    /**
     * 判断是否是分光器
     * @param businessName
     * @return
     */
    public static POSMatchVo isPOS(String businessName) {
        String u = businessName.toUpperCase();
        Matcher matcher = RULE_POS.matcher(u);
        if(matcher.find()){
            POSMatchVo posMatchVo = new POSMatchVo();
            Integer posNo = Integer.valueOf(matcher.group(1)); // 只捕获非0结尾数字
            posMatchVo.setPosNo(posNo);
            Matcher outMatcher = outPattern.matcher(u);
            Matcher inMatcher = inPattern.matcher(u);
            if (outMatcher.find()) {
                String digit = outMatcher.group(1);
                posMatchVo.setInOrOut("out");
                posMatchVo.setPortNo(digit);
                return posMatchVo;
            } else if (inMatcher.find()) {
                String digit = inMatcher.group(1);
                posMatchVo.setInOrOut("in");
                posMatchVo.setPortNo(digit);
                return posMatchVo;
            } else {
                return null;
            }
        }else {
            return null;
        }
    }

    /**
     * 过滤出路由
     * @param lines
     * @return
     */
    public static List<OcrPort> filterRoute(List<OcrPort> lines) {
        List<OcrPort> kept = new ArrayList<>();
        for (OcrPort line : lines) {
            if (line.getBusinessInfoRoute() != null && !"".equals(line.getBusinessInfoRoute()) ) {
                // 拼接业务信息和端口信息
                line.setBusinessName(line.getBusinessName()+"**"+line.getBusinessInfoRoute());
                portsFrTo.add(line);
            }else {
                kept.add(line);
            }
        }
        return kept;
    }

    /**
     * 是否至少包含两个关键词
     * @param input
     * @param keywords
     * @return
     */
    public static boolean containsAtLeastTwoFrKeywords(String input, String[] keywords) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        int count = 0;
        for (String keyword : afterFrAndCouldNotTextMatch) {
            int index = 0;
            while ((index = input.indexOf(keyword, index)) != -1) {
                count++;
                index += keyword.length(); // 继续往后找
                if (count >= 2) {
                    return true; // 提前返回，性能更好
                }
            }
        }
        return false;
    }

    private static final Pattern PMultiple = Pattern.compile(
            "(?i)(?:T[O0]|FR|R|ODF|O1F|DF)[,:.]?\\s*" +
                    "(?:([^\\d][^/]*)?/)?" +
                    "((?:\\d{1,2}-){3,4}\\d{1,2})" +
                    "(/\\d{1,2})?" +
                    "(?:\\s*[→～~至到\\-—]\\s*(\\d{1,2}))?"  // 新增：匹配范围符号和结束数字
    );

    public static List<AddrResult> extractMultiple(String text) {
        List<AddrResult> result = new ArrayList<>();
        Matcher m = PMultiple.matcher(text);

        while (m.find()) {
            // 获取匹配的起始位置
            int start = m.start();

            // 检查匹配结果前面是否包含 "OLT"
            if (start >= 3) {
                String beforeMatch = text.substring(start - 3, start);
                System.out.println(beforeMatch);
                if (beforeMatch.toUpperCase().contains("OL")) {
                    continue; // 跳过这个匹配结果
                }
            }

            String mainAddr = m.group(2);
            String slashPart = m.group(3);
            String rangeEndStr = m.group(4);

            String[] parts = mainAddr.split("-");
            if (parts.length >= 3) {
                String last3 = String.join("-", Arrays.copyOfRange(
                        parts, parts.length - 3, parts.length));

                if (rangeEndStr != null && !rangeEndStr.isEmpty()) {
                    try {
                        int startNum = Integer.parseInt(parts[parts.length - 1]);
                        int end = Integer.parseInt(rangeEndStr);

                        if (startNum <= end) {
                            for (int i = startNum; i <= end; i++) {
                                String[] newParts = Arrays.copyOf(parts, parts.length);
                                newParts[newParts.length - 1] = String.valueOf(i);

                                String newLast3 = String.join("-", Arrays.copyOfRange(
                                        newParts, newParts.length - 3, newParts.length));

                                if (slashPart != null) {
                                    result.add(new AddrResult(newLast3 + slashPart, true));
                                } else {
                                    result.add(new AddrResult(newLast3, false));
                                }
                            }
                        } else {
                            if (slashPart != null) {
                                result.add(new AddrResult(last3 + slashPart, true));
                            } else {
                                result.add(new AddrResult(last3, false));
                            }
                        }
                    } catch (NumberFormatException e) {
                        if (slashPart != null) {
                            result.add(new AddrResult(last3 + slashPart, true));
                        } else {
                            result.add(new AddrResult(last3, false));
                        }
                    }
                } else {
                    if (slashPart != null) {
                        result.add(new AddrResult(last3 + slashPart, true));
                    } else {
                        result.add(new AddrResult(last3, false));
                    }
                }
            }
        }

        // 对结果进行排序：按百位、十位、个位排序
        result.sort((a, b) -> {
            String[] aParts = a.value.split("-");
            String[] bParts = b.value.split("-");

            // 比较百位（第一部分）
            int hundredCompare = Integer.compare(Integer.parseInt(aParts[0]), Integer.parseInt(bParts[0]));
            if (hundredCompare != 0) {
                return hundredCompare;
            }

            // 比较十位（第二部分）
            int tenCompare = Integer.compare(Integer.parseInt(aParts[1]), Integer.parseInt(bParts[1]));
            if (tenCompare != 0) {
                return tenCompare;
            }

            // 比较个位（第三部分）
            return Integer.compare(Integer.parseInt(aParts[2]), Integer.parseInt(bParts[2]));
        });
        return result;
    }
    /**
     * 地址拓展属性
     */
    public static class AddrResult {
        public String value;
        public boolean hasSlash; // 是否为 “/扩展” 类型

        public AddrResult(String value, boolean hasSlash) {
            this.value = value;
            this.hasSlash = hasSlash;
        }

        @Override
        public String toString() {
            return (hasSlash ? "[斜杠地址] " : "[普通地址] ") + value;
        }
    }
}
