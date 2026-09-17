package org.collegealma.guacamole_seb_validator;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.guacamole.GuacamoleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class SebValidationResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(SebValidationResource.class);

    private final SebValidation validation;

    public SebValidationResource() {
        this(new SebValidation());
    }

    SebValidationResource(SebValidation validation) {
        this.validation = validation;
    }

    @POST
    @Path("validate")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validate(
            @FormParam("configKeyHash") String configKeyHash,
            @FormParam("pageUrl") String pageUrl,
            @FormParam("sebVersion") String sebVersion,
            @Context HttpServletRequest request) throws GuacamoleException {

        try {
            if (!validation.validateJavaScriptValue(request, pageUrl, configKeyHash)) {
                SebValidation.clearSessionValidation(request);
                LOGGER.warn("Rejected SEB JavaScript API validation from {}", request.getRemoteAddr());
                return Response.status(Response.Status.FORBIDDEN)
                        .entity("{\"accepted\":false}")
                        .cacheControl(noStore())
                        .build();
            }

            // Validate the lifetime before granting approval.
            long maxAgeSeconds = validation.getMaxAgeSeconds();
            Response response = Response.ok("{\"accepted\":true,\"maxAgeSeconds\":" + maxAgeSeconds + "}")
                    .cacheControl(noStore())
                    .build();
            validation.markSessionValidated(request, sebVersion);
            LOGGER.info("Accepted SEB JavaScript API validation from {}", request.getRemoteAddr());
            return response;
        }
        catch (GuacamoleException | RuntimeException exception) {
            SebValidation.clearSessionValidation(request);
            throw exception;
        }
    }

    private static javax.ws.rs.core.CacheControl noStore() {
        javax.ws.rs.core.CacheControl cacheControl = new javax.ws.rs.core.CacheControl();

        cacheControl.setNoStore(true);
        cacheControl.setNoCache(true);

        return cacheControl;
    }
}
