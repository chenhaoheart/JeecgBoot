package org.jeecg.modules.airag.voice.util;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.consts.LLMConsts;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.service.impl.AiragDefaultModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;


/**
* @Description: <p>统一封装 语音 HTTP API 调用逻辑，供语音模块和视频模块复用</p>
*
* @author: wangshuai
* @date: 2026/3/13 16:19
*/
@Slf4j
@Component
public class VoiceApiHelper {

    @Autowired
    private AiragDefaultModelService airagDefaultModelService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 调用 TTS API 生成语音文件（使用数据库默认语音模型的 voice 和 speed）
     *
     * @param text      要转换的文本
     * @param audioPath 音频输出路径
     */
    public void generateAudio(String text, Path audioPath) throws IOException, InterruptedException {
        AiragModel voiceModel = airagDefaultModelService.getRequiredDefaultByType(LLMConsts.MODEL_TYPE_VOICE, "语音");
        JSONObject voiceParams = AiragDefaultModelService.extractParams(voiceModel);
        double defaultSpeed = voiceParams.getDoubleValue("speed");
        generateAudio(text, audioPath, voiceParams.getString("voice"), defaultSpeed > 0 ? defaultSpeed : 1.0);
    }

    /**
     * 调用 TTS API 生成语音文件（自定义voice和speed）
     *
     * @param text      要转换的文本
     * @param audioPath 音频输出路径
     * @param voice     声色
     * @param speed     语速
     */
    public void generateAudio(String text, Path audioPath, String voice, double speed) throws IOException, InterruptedException {
        AiragModel voiceModel = airagDefaultModelService.getRequiredDefaultByType(LLMConsts.MODEL_TYPE_VOICE, "语音");
        JSONObject voiceParams = AiragDefaultModelService.extractParams(voiceModel);
        String apiKey = AiragDefaultModelService.extractApiKey(voiceModel);
        String apiHost = voiceModel.getBaseUrl();
		if (apiHost == null || apiHost.isBlank()) {
			throw new IllegalStateException("语音模型 base_url 未配置，请前往 AI模型管理 中设置默认语音模型的接口地址");
		}
        String url = apiHost.endsWith("/") ? apiHost + "audio/speech" : apiHost + "/audio/speech";

        JSONObject body = new JSONObject();
        body.put("model", voiceModel.getModelName());
        body.put("input", text);
        body.put("voice", voice);
        body.put("speed", speed);
        body.put("response_format", "wav");

        log.info("TTS请求: url={}, model={}, voice={}, speed={}, textLength={}", url, voiceModel.getModelName(), voice, speed, text.length());

        Integer timeout = voiceParams.getInteger("timeout");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                .timeout(Duration.ofSeconds(timeout == null || timeout <= 0 ? 60 : timeout))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String errorBody = new String(response.body().readAllBytes());
            log.error("TTS API调用失败: status={}, body={}", response.statusCode(), errorBody);
            throw new RuntimeException("TTS API调用失败，状态码: " + response.statusCode() + "，" + errorBody);
        }

        try (InputStream is = response.body()) {
            Files.copy(is, audioPath, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("TTS语音已生成: {}", audioPath);
    }
}
