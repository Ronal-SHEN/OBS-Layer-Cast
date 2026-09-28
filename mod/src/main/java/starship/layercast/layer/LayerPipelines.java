package starship.layercast.layer;

//? if >=26.2
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Replacements for vanilla GUI pipelines whose blending only works on top of an opaque picture (layers are drawn on
 * transparent black and composited later, so such elements must be written as colour + alpha instead), and the
 * full-screen passes that split the name tags out of the world picture ({@link NameTagLayer}).
 */
public final class LayerPipelines {
    //? if >=26.3 {
    /*private static final String VIGNETTE_SHADER = "core/vignette_layer_v2";*/
    //?} else
    private static final String VIGNETTE_SHADER = "core/vignette_layer";

    /** The vignette as black with alpha instead of darkening the picture below it. */
    public static final RenderPipeline VIGNETTE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("layercast", "pipeline/vignette_layer"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("layercast", VIGNETTE_SHADER))
        .build();

    /** Depth buffer value where nothing was drawn (reverse-Z since 26.2). */
    //? if >=26.2 {
    private static final float CLEARED_DEPTH = 0.0F;
    //?} else
    /*private static final float CLEARED_DEPTH = 1.0F;*/

    /** Samplers of {@link #NAME_TAG_GAME}. */
    public static final String[] NAME_TAG_GAME_SAMPLERS = {"NameTagSampler", "FrameSampler", "WorldSampler", "HandDepthSampler"};
    /** Samplers of {@link #NAME_TAG_MASK}. */
    public static final String[] NAME_TAG_MASK_SAMPLERS = {"HandDepthSampler"};

    /** Takes the name tags out of the game layer (a copy of the finished world) wherever the name tag layer has content. */
    public static final RenderPipeline NAME_TAG_GAME = fullscreen("name_tag_game", NAME_TAG_GAME_SAMPLERS);
    /** Erases the name tag layer where the first-person hand covers it. */
    public static final RenderPipeline NAME_TAG_MASK = fullscreen("name_tag_mask", NAME_TAG_MASK_SAMPLERS);

    private LayerPipelines() {
    }

    /** A pipeline without blending that draws one full-screen triangle ({@code core/screenquad}). */
    private static RenderPipeline fullscreen(String name, String[] samplers) {
        //? if >=26.2 {
        BindGroupLayout.Builder layout = BindGroupLayout.builder();
        for (String sampler : samplers) {
            //? if >=26.3 {
            /*layout.withUniform(sampler, com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER);
            *///?} else
            layout.withSampler(sampler);
        }
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withBindGroupLayout(layout.build());
        //?} else {
        /*RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET);
        for (String sampler : samplers) {
            builder.withSampler(sampler);
        }
        *///?}
        return builder
            .withLocation(Identifier.fromNamespaceAndPath("layercast", "pipeline/" + name))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("layercast", "core/" + name))
            .withShaderDefine("CLEARED_DEPTH", CLEARED_DEPTH)
            .build();
    }

    /** The pipeline to draw an element with when it goes into a layer. */
    public static RenderPipeline forLayer(RenderPipeline pipeline) {
        return pipeline == RenderPipelines.VIGNETTE ? VIGNETTE : pipeline;
    }
}
