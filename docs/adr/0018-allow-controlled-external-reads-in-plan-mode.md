# Allow controlled External Reads in Plan Mode

Plan Mode may use structured External Read tools when the base Session policy and Outbound Disclosure rules permit them, while External Mutation remains forbidden regardless of Tool Approval. Arbitrary shell execution stays offline because Runtime cannot prove that arbitrary programs will only read, and sending query or repository data outward is governed independently from the remote operation's state effect. This preserves web research without turning Plan Mode into either an offline mode or an implicit data-disclosure grant.
