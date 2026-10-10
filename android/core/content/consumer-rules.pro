# R8 rules that apps using this library need (the phonotrainer.android.library convention plugin wires this file).
# None: the JSON readers are @Serializable classes looked up statically through their generated serializers, which
# kotlinx-serialization's own rules keep; nothing is read by reflection.
