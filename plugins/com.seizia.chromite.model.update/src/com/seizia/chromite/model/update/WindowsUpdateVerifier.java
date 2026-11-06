/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seizia.chromite.model.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The checks of a downloaded Windows setup ({@code Chromite-<v>-x64-setup.exe}) before it runs.
 *
 * 설계: Turbrix 데스크톱 {@code updateFiles.cjs}의 확인을 옮겼다(docs/chromite/auto-update.md "Windows 확인").
 * <ol>
 *     <li>크기와 SHA-256이 목록과 같다.</li>
 *     <li>Authenticode 상태가 {@code Valid}다(Windows가 신뢰하는 체인, 파일이 서명 뒤 바뀌지 않음).</li>
 *     <li>서명자 인증서의 주체가 정확히 우리 게시자 {@link #PUBLISHER}다(Azure Artifact Signing이 SEIZIA에 발급,
 *     docs/chromite/signing.md). 서명 키는 Azure에 있고 CI만 쓴다. 사이트가 뚫려도 이 서명은 만들 수 없다.</li>
 *     <li>타임스탬프가 있다(인증서 만료 뒤에도 서명이 유효).</li>
 *     <li>파일의 ProductName이 {@link #PRODUCT_NAME}이다. SEIZIA는 다른 제품(Turbrix)도 같은 게시자로 서명하므로,
 *     게시자만 보면 서명된 다른 제품의 설치 파일을 Chromite 업데이트로 내밀 수 있다(계약에 없던 확인, 보고함).</li>
 *     <li>파일의 ProductVersion이 목록의 버전과 같다(서명된 옛 설치 파일을 새 버전으로 내미는 것 차단).</li>
 * </ol>
 * 서명과 버전 정보는 Windows가 직접 읽는다: PowerShell 5.1의 {@code Get-AuthenticodeSignature}(내부에서 WinVerifyTrust)와
 * 파일의 버전 리소스. 파일 경로는 명령줄이 아니라 환경 변수로 넘기고, 스크립트는 {@code -EncodedCommand}로 넘긴다
 * (스크립트 파일이 아니라서 실행 정책과 상관없다). 회사 PC에서 PowerShell이 막혀 있으면 {@link UpdateStep#CHECKER}로 실패한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
public final class WindowsUpdateVerifier implements UpdateVerifier {

    /**
     * The signer of every SEIZIA Windows release (Azure Artifact Signing, the same subject Turbrix checks). Fixed forever.
     */
    public static final String PUBLISHER = "CN=Seizia, O=Seizia, L=Cheongju-si, S=North Chungcheong, C=KR"; //$NON-NLS-1$
    public static final String PRODUCT_NAME = "Chromite"; //$NON-NLS-1$
    private static final String STATUS_VALID = "Valid"; //$NON-NLS-1$
    private static final String FILE_VARIABLE = "CHROMITE_UPDATE_FILE"; //$NON-NLS-1$
    private static final String MARKER = "CHROMITE-SIGNATURE:"; //$NON-NLS-1$
    private static final Duration CHECK_TIME = Duration.ofSeconds(60);
    private static final String SCRIPT = String.join("\n", //$NON-NLS-1$
        "$ErrorActionPreference = 'Stop'", //$NON-NLS-1$
        "$ProgressPreference = 'SilentlyContinue'", //$NON-NLS-1$
        "$p = $env:" + FILE_VARIABLE, //$NON-NLS-1$
        "$s = Get-AuthenticodeSignature -LiteralPath $p", //$NON-NLS-1$
        "$v = (Get-Item -LiteralPath $p).VersionInfo", //$NON-NLS-1$
        "$o = [pscustomobject]@{ status = [string]$s.Status; subject = [string]$s.SignerCertificate.Subject; " //$NON-NLS-1$
            + "timestamp = ($null -ne $s.TimeStamperCertificate); version = [string]$v.ProductVersion; name = [string]$v.ProductName }", //$NON-NLS-1$
        "[Console]::Out.WriteLine('" + MARKER + "' + ($o | ConvertTo-Json -Compress))"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * What Windows says of a file's signature and version information.
     */
    public record Signature(@NotNull String status, @NotNull String subject, boolean timestamp,
                            @NotNull String productVersion, @NotNull String productName) {
    }

    /**
     * Reads a file's signature; null when it could not be read.
     */
    public interface SignatureReader {
        @Nullable
        Signature read(@NotNull Path file) throws InterruptedException;
    }

    private final String publisher;
    private final String productName;
    private final SignatureReader reader;

    public WindowsUpdateVerifier() {
        this(PUBLISHER, PRODUCT_NAME, WindowsUpdateVerifier::readSignature);
    }

    /**
     * @param publisher   the exact signer subject to require ({@link #PUBLISHER} except in the dev test mode)
     * @param productName the ProductName to require ({@link #PRODUCT_NAME} except in the dev test mode)
     */
    public WindowsUpdateVerifier(@NotNull String publisher, @NotNull String productName, @NotNull SignatureReader reader) {
        this.publisher = publisher;
        this.productName = productName;
        this.reader = reader;
    }

    @NotNull
    @Override
    public Verdict verify(@NotNull Path file, @NotNull UpdateEntry entry) throws InterruptedException {
        UpdateStep bytes = UpdateVerifier.checkBytes(file, entry);
        if (bytes != null) {
            return Verdict.fail(bytes);
        }
        Signature signature = reader.read(file);
        UpdateStep step = checkSignature(signature, entry.version());
        return step == null ? Verdict.OK : Verdict.fail(step);
    }

    /**
     * The signature checks in their order; null when all pass.
     */
    @Nullable
    UpdateStep checkSignature(@Nullable Signature signature, @NotNull String version) {
        if (signature == null) {
            return UpdateStep.CHECKER;
        }
        if (!STATUS_VALID.equals(signature.status())) {
            return UpdateStep.SIGNATURE;
        }
        if (!publisher.equals(signature.subject())) {
            return UpdateStep.PUBLISHER;
        }
        if (!signature.timestamp()) {
            return UpdateStep.TIMESTAMP;
        }
        if (!productName.equals(signature.productName())) {
            return UpdateStep.PRODUCT;
        }
        if (!version.equals(signature.productVersion())) {
            return UpdateStep.VERSION;
        }
        return null;
    }

    /**
     * Whether {@code file} is Authenticode {@code Valid} with a timestamp and signed by {@code publisher}: the dev test
     * mode refuses to run in a launcher signed for release (docs/chromite/auto-update.md "시험 모드").
     */
    public static boolean isSignedBy(@NotNull Path file, @NotNull String publisher) throws InterruptedException {
        Signature signature = readSignature(file);
        return signature != null && STATUS_VALID.equals(signature.status()) && publisher.equals(signature.subject());
    }

    /**
     * PowerShell 5.1 ({@code %SystemRoot%\System32\WindowsPowerShell\v1.0}) reads the signature and the version
     * resource; the file reaches it through the environment.
     */
    @Nullable
    public static Signature readSignature(@NotNull Path file) throws InterruptedException {
        UpdateProcess.Result result = UpdateProcess.run(
            List.of(powerShell(), "-NoLogo", "-NoProfile", "-NonInteractive", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "-EncodedCommand", UpdateProcess.encodePowerShell(SCRIPT)), //$NON-NLS-1$
            Map.of(FILE_VARIABLE, file.toAbsolutePath().toString()), CHECK_TIME);
        if (result.code() != 0) {
            return null;
        }
        for (String line : result.output().split("\\R")) { //$NON-NLS-1$
            if (line.startsWith(MARKER)) {
                return parse(line.substring(MARKER.length()));
            }
        }
        return null;
    }

    @Nullable
    static Signature parse(@NotNull String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!(root instanceof JsonObject o)) {
                return null;
            }
            return new Signature(text(o, "status"), text(o, "subject"), //$NON-NLS-1$ //$NON-NLS-2$
                o.get("timestamp") instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean(), //$NON-NLS-1$
                text(o, "version").trim(), text(o, "name").trim()); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (RuntimeException e) {
            return null;
        }
    }

    @NotNull
    private static String text(@NotNull JsonObject o, @NotNull String name) {
        return o.get(name) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : ""; //$NON-NLS-1$
    }

    @NotNull
    static String powerShell() {
        String systemRoot = System.getenv("SystemRoot"); //$NON-NLS-1$
        return Path.of(systemRoot != null ? systemRoot : "C:\\Windows", //$NON-NLS-1$
            "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
