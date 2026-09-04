package com.example.config;

import com.example.service.EmbeddingService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class EmbeddingModelConfig {

    /**
     * Spring AI's PineconeVectorStore autoconfiguration requires an EmbeddingModel bean.
     * The Google GenAI starter doesn't auto-register one in 1.1.8, so we wire our
     * existing EmbeddingService (plain HTTP to gemini-embedding-001) into the interface.
     */
    @Bean
    public EmbeddingModel embeddingModel(EmbeddingService embeddingService) {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<String> texts = request.getInstructions();
                try {
                    List<List<Float>> vectors = embeddingService.batchEmbed(texts);
                    List<Embedding> embeddings = new ArrayList<>();
                    for (int i = 0; i < vectors.size(); i++) {
                        List<Float> vec = vectors.get(i);
                        float[] arr = new float[vec.size()];
                        for (int j = 0; j < vec.size(); j++) arr[j] = vec.get(j);
                        embeddings.add(new Embedding(arr, i));
                    }
                    return new EmbeddingResponse(embeddings);
                } catch (Exception e) {
                    throw new RuntimeException("Gemini embedding failed", e);
                }
            }

            @Override
            public float[] embed(String text) {
                try {
                    List<Float> vec = embeddingService.embed(text);
                    float[] arr = new float[vec.size()];
                    for (int i = 0; i < vec.size(); i++) arr[i] = vec.get(i);
                    return arr;
                } catch (Exception e) {
                    throw new RuntimeException("Gemini embedding failed", e);
                }
            }

            @Override
            public float[] embed(Document document) {
                return embed(document.getText());
            }

            @Override
            public int dimensions() {
                return 768;
            }
        };
    }
}
