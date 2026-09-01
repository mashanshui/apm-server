package com.shanshui.apmserver.web;

import com.shanshui.apmserver.config.StackParserProperties;
import com.shanshui.apmserver.domain.StackArtifactParseResponse;
import com.shanshui.apmserver.domain.AuthenticatedApp;
import com.shanshui.apmserver.service.InvalidStackArtifactException;
import com.shanshui.apmserver.service.InvalidStackArtifactRequestException;
import com.shanshui.apmserver.service.PayloadTooLargeException;
import com.shanshui.apmserver.service.AppKeyAuthenticator;
import com.shanshui.apmserver.service.StackArtifactParseService;
import com.shanshui.apmserver.service.UnsupportedMediaTypeException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

@RestController
@RequestMapping("/ingest/v1")
public class StackArtifactController {

    public static final String ARTIFACT_MEDIA_TYPE = "application/vnd.shanshui.rheajank+zip";

    private static final MediaType ARTIFACT_MEDIA = MediaType.parseMediaType(ARTIFACT_MEDIA_TYPE);

    private final AppKeyAuthenticator authenticator;
    private final StackArtifactParseService parseService;
    private final StackParserProperties properties;

    public StackArtifactController(AppKeyAuthenticator authenticator,
                                   StackArtifactParseService parseService,
                                   StackParserProperties properties) {
        this.authenticator = authenticator;
        this.parseService = parseService;
        this.properties = properties;
    }

    @PostMapping("/stack-artifacts:parse")
    public ResponseEntity<StackArtifactParseResponse> parse(
            @RequestHeader(value = "X-App-Key", required = false) String appKey,
            HttpServletRequest request) {
        AuthenticatedApp app = authenticator.authenticate(appKey);
        validateMediaType(request.getContentType());
        long contentLength = request.getContentLengthLong();
        if (contentLength == 0) {
            throw new InvalidStackArtifactRequestException(
                    "INVALID_STACK_ARTIFACT_REQUEST", "堆栈产物请求体不能为空");
        }
        if (contentLength > properties.getMaxArtifactBytes()) {
            throw new PayloadTooLargeException("堆栈产物超过大小上限");
        }
        try (InputStream limited = new LimitedInputStream(request.getInputStream(),
                properties.getMaxArtifactBytes(), "堆栈产物超过大小上限");
             PushbackInputStream input = new PushbackInputStream(limited, 1)) {
            int first = input.read();
            if (first < 0) {
                throw new InvalidStackArtifactRequestException(
                        "INVALID_STACK_ARTIFACT_REQUEST", "堆栈产物请求体不能为空");
            }
            input.unread(first);
            return ResponseEntity.ok(parseService.parse(app, input));
        } catch (InvalidStackArtifactRequestException | InvalidStackArtifactException
                 | PayloadTooLargeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new InvalidStackArtifactException(
                    "INVALID_STACK_ARTIFACT", "堆栈产物读取失败", ex);
        }
    }

    private void validateMediaType(String contentType) {
        try {
            if (contentType == null || !ARTIFACT_MEDIA.isCompatibleWith(MediaType.parseMediaType(contentType))) {
                throw new UnsupportedMediaTypeException(
                        "堆栈产物只支持 " + ARTIFACT_MEDIA_TYPE);
            }
        } catch (IllegalArgumentException ex) {
            throw new UnsupportedMediaTypeException(
                    "堆栈产物只支持 " + ARTIFACT_MEDIA_TYPE);
        }
    }
}
