(function sebValidationBootstrap() {
    "use strict";

    const VALIDATE_PATH = "api/ext/seb-validation/validate";
    const HASH_PATTERN = /^[0-9a-f]{64}$/i;

    let validationPending = false;
    let renewalTimer;

    const MESSAGES = {
        keyApi: "This SEB version cannot provide its configuration key. Use the SEB version supplied for your exam.",
        keyTimeout: "SEB did not return its configuration key in time. Reopen your exam configuration and try again.",
        keyFormat: "Unable to read the SEB configuration key: the returned hash must contain 64 hexadecimal characters. Reopen your exam configuration.",
        network: "Cannot reach the SEB validation server. Check your connection and try again.",
        timeout: "The SEB validation server took too long to respond. Try again.",
        unauthorized: "The validation request was not authorized. Reload the Guacamole page and try again.",
        rejected: "SEB validation was rejected. Reopen the approved exam configuration using the configured Guacamole start URL.",
        endpoint: "The SEB validation endpoint was not found. Contact your administrator.",
        server: "The server could not complete SEB validation. Try again or contact your administrator.",
        response: "The server returned an unexpected SEB validation response. Contact your administrator.",
        generic: "Safe Exam Browser validation failed. Try again or contact your administrator."
    };

    // Only errors created here may supply user-facing text.
    class ValidationError extends Error {}

    function statusMessage(status) {
        if (status === 401)
            return MESSAGES.unauthorized;
        if (status === 403)
            return MESSAGES.rejected;
        if (status === 404)
            return MESSAGES.endpoint;
        if (status >= 500 && status <= 599)
            return MESSAGES.server;

        return MESSAGES.generic + " (HTTP " + status + ")";
    }

    function showFailure(message) {
        let notice = document.getElementById("seb-validation-notice");

        if (!notice) {
            notice = document.createElement("div");
            notice.id = "seb-validation-notice";
            notice.setAttribute("role", "alert");
            notice.style.cssText = [
                "position:fixed",
                "z-index:2147483647",
                "left:0",
                "right:0",
                "top:0",
                "padding:12px",
                "background:#8b0000",
                "color:white",
                "font:16px sans-serif",
                "text-align:center"
            ].join(";");
            document.body.appendChild(notice);
        }

        notice.textContent = message;
    }

    function readKeys(seb) {
        return new Promise(function(resolve, reject) {
            if (seb.security.configKey) {
                resolve();
                return;
            }

            if (typeof seb.security.updateKeys !== "function") {
                reject(new ValidationError(MESSAGES.keyApi));
                return;
            }

            const timeout = window.setTimeout(function() {
                reject(new ValidationError(MESSAGES.keyTimeout));
            }, 5000);

            try {
                seb.security.updateKeys(function() {
                    window.clearTimeout(timeout);
                    resolve();
                });
            }
            catch (error) {
                window.clearTimeout(timeout);
                reject(error);
            }
        });
    }

    async function validate() {
        if (validationPending)
            return;

        validationPending = true;
        window.clearTimeout(renewalTimer);
        renewalTimer = undefined;

        let nextDelay;
        let requestTimeout;
        let timedOut = false;

        try {
            const seb = window.SafeExamBrowser;
            // Ordinary browsers and header-capable SEB clients need no handshake.
            if (!seb || !seb.security)
                return;

            nextDelay = 15000;
            await readKeys(seb);

            const configKeyHash = seb.security.configKey;

            if (typeof configKeyHash !== "string" || !HASH_PATTERN.test(configKeyHash)) {
                throw new ValidationError(MESSAGES.keyFormat);
            }

            // SEB hashes the absolute page URL without its fragment.
            const pageUrl = window.location.href.split("#", 1)[0];
            const endpoint = new URL(VALIDATE_PATH, document.baseURI);
            const body = new URLSearchParams();

            body.set("configKeyHash", configKeyHash.toLowerCase());
            body.set("pageUrl", pageUrl);
            body.set("sebVersion", String(seb.version || ""));

            const requestStarted = performance.now();
            const controller = new AbortController();
            requestTimeout = window.setTimeout(function() {
                timedOut = true;
                controller.abort();
            }, 10000);

            let response;
            try {
                response = await fetch(endpoint.toString(), {
                    method: "POST",
                    credentials: "same-origin",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: body.toString(),
                    cache: "no-store",
                    signal: controller.signal
                });
            }
            catch (error) {
                throw new ValidationError(timedOut ? MESSAGES.timeout : MESSAGES.network);
            }

            if (!response.ok) {
                throw new ValidationError(statusMessage(response.status));
            }

            let result;
            try {
                result = await response.json();
            }
            catch (error) {
                throw new ValidationError(timedOut ? MESSAGES.timeout : MESSAGES.response);
            }

            if (timedOut)
                throw new ValidationError(MESSAGES.timeout);

            if (!result || result.accepted !== true
                    || !Number.isInteger(result.maxAgeSeconds)
                    || result.maxAgeSeconds < 30 || result.maxAgeSeconds > 3600) {
                throw new ValidationError(MESSAGES.response);
            }

            nextDelay = Math.max(0, Math.min(result.maxAgeSeconds * 500, 60000)
                    - (performance.now() - requestStarted));
            const notice = document.getElementById("seb-validation-notice");
            if (notice) {
                notice.remove();
            }
        }
        catch (error) {
            showFailure(error instanceof ValidationError ? error.message : MESSAGES.generic);
        }
        finally {
            if (requestTimeout !== undefined) window.clearTimeout(requestTimeout);
            validationPending = false;
            if (nextDelay !== undefined) {
                renewalTimer = window.setTimeout(validate, nextDelay);
            }
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", validate, { once: true });
    }
    else {
        validate();
    }

    // Background tabs may throttle timers; validate immediately on return.
    document.addEventListener("visibilitychange", function() {
        if (document.visibilityState === "visible") return validate();
    });
})();
